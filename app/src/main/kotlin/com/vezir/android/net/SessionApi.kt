package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * API client for session list, detail, sync-now, share, and artifact
 * download. Mirrors the server endpoints in vezir/server/sessions.py.
 *
 * v0.5.0: requires a [teamId] for vezir-server v0.7.0; the X-Team-Id
 * header is added on every request via [HttpClients.authHeaders].
 */
class SessionApi(
    private val baseUrl: String,
    private val token: String,
    private val teamId: String?,
    caPem: String? = null,
    externalClient: OkHttpClient? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val EMPTY_JSON = "{}".toRequestBody("application/json".toMediaType())

        /**
         * Extract the filename from a Content-Disposition header
         * (``attachment; filename="20260824_brainstorm_phoenix.pdf"``, as
         * sent by vezir-server v0.14.1+).  Returns null when absent.
         */
        fun filenameFromDisposition(disposition: String?): String? {
            if (disposition.isNullOrBlank()) return null
            // Prefer the RFC 5987 form, then the plain quoted form.
            val utf8 = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.get(1)
            if (utf8 != null) {
                return try {
                    java.net.URLDecoder.decode(utf8, "UTF-8")
                } catch (_: Exception) {
                    utf8
                }
            }
            val quoted = Regex("filename=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.get(1)
            if (quoted != null) return quoted
            val bare = Regex("filename=([^;\\s]+)", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.get(1)
            return bare
        }
    }

    private val client: OkHttpClient = externalClient
        ?: HttpClients.build(caPem)

    @Serializable
    data class Session(
        val id: String,
        val github: String? = null,
        val title: String? = null,
        val status: String,
        val summary_preset: String? = null,
        val auto_label_enabled: Int? = null,
        val sync_enabled: Int? = null,
        val personal: Int? = null,
        val created_at: String? = null,
        val updated_at: String? = null,
        val error: String? = null,
        val summary_error: String? = null,
        val sync_error: String? = null,
        val artifacts: String? = null,
    ) {
        /** Parse the JSON-encoded artifacts string into a map. */
        val artifactMap: Map<String, String>
            get() {
                if (artifacts.isNullOrBlank()) return emptyMap()
                return try {
                    val obj = json.decodeFromString(JsonObject.serializer(), artifacts)
                    obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
                } catch (_: Exception) {
                    emptyMap()
                }
            }

        val isPersonal: Boolean get() = (personal ?: 0) != 0
        val isTerminal: Boolean get() = status in setOf("done", "error", "empty")
    }

    @Serializable
    data class SessionList(val sessions: List<Session>)

    /**
     * Result of a mutating call (set-title, delete). [warning] carries the
     * server's non-fatal advisory (e.g. "already synced; folder not renamed
     * / pushed copy remains") when present, otherwise null.
     */
    data class Mutation(val ok: Boolean, val warning: String?)

    sealed class Result<out T> {
        data class Ok<T>(val data: T) : Result<T>()
        data class HttpError(val code: Int, val message: String) : Result<Nothing>()
        data class NetworkError(val cause: Throwable) : Result<Nothing>()
    }

    private fun parseMutation(body: String): Mutation =
        try {
            val o = JSONObject(body)
            Mutation(
                ok = o.optBoolean("ok", true),
                warning = o.optString("warning", "").ifBlank { null },
            )
        } catch (_: Exception) {
            Mutation(ok = true, warning = null)
        }

    /** Pull the server's ``detail`` message out of an error body, else message. */
    private fun errorDetail(resp: okhttp3.Response): String =
        try {
            val b = resp.peekBody(64 * 1024).string()
            JSONObject(b).optString("detail", resp.message).ifBlank { resp.message }
        } catch (_: Exception) {
            resp.message
        }

    suspend fun getSessions(limit: Int = 50, since: String? = null): Result<List<Session>> =
        withContext(Dispatchers.IO) {
            var url = "${baseUrl.trimEnd('/')}/api/sessions?limit=$limit"
            if (since != null) url += "&since=${java.net.URLEncoder.encode(since, "UTF-8")}"
            val req = HttpClients.authHeaders(
                Request.Builder().url(url), token, teamId,
            ).get().build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use Result.HttpError(
                            resp.code, "empty body",
                        )
                        Result.Ok(json.decodeFromString(SessionList.serializer(), body).sessions)
                    } else {
                        Result.HttpError(resp.code, resp.message)
                    }
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    suspend fun getSession(sessionId: String): Result<Session> =
        withContext(Dispatchers.IO) {
            val req = HttpClients.authHeaders(
                Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId"),
                token, teamId,
            ).get().build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use Result.HttpError(
                            resp.code, "empty body",
                        )
                        Result.Ok(json.decodeFromString(Session.serializer(), body))
                    } else {
                        Result.HttpError(resp.code, resp.message)
                    }
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    /**
     * Trigger a retroactive git sync. An optional [meetingType] overrides
     * the target folder slug (vezir server >= 0.6.x: JSON body
     * ``{"meeting_type": "<slug>"}``); the server re-slugifies and
     * validates it, returning 400 on an invalid value.
     */
    suspend fun syncNow(
        sessionId: String,
        meetingType: String? = null,
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val body = if (meetingType.isNullOrBlank()) {
                EMPTY_JSON
            } else {
                JSONObject().put("meeting_type", meetingType).toString()
                    .toRequestBody("application/json".toMediaType())
            }
            val req = HttpClients.authHeaders(
                Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/session/$sessionId/sync")
                    .header("Accept", "application/json"),
                token, teamId,
            ).post(body).build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.Ok(true)
                    else Result.HttpError(resp.code, resp.message)
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    /**
     * Re-run summarization. [preset] optionally switches the backend;
     * [language] (vezir server >= 0.12.0) optionally regenerates in another
     * language — ``"auto"`` (or null) rewrites the primary summary, any
     * other code (``en/de/fr/es/tr/fa``) produces an ADDITIONAL
     * ``*.summary.<lang>.md`` artifact. Invalid language -> server 400.
     */
    suspend fun retrySummary(
        sessionId: String,
        preset: String? = null,
        language: String? = null,
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val obj = JSONObject()
            if (preset != null) obj.put("preset", preset)
            // "auto" is the server default; omit it to keep the body minimal.
            if (!language.isNullOrBlank() && language != "auto") {
                obj.put("language", language)
            }
            val body = obj.toString().toRequestBody("application/json".toMediaType())
            val req = HttpClients.authHeaders(
                Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId/retry-summary"),
                token, teamId,
            ).post(body).build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.Ok(true)
                    else Result.HttpError(resp.code, resp.message)
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    /**
     * Re-run voiceprint auto-labeling for an already-transcribed session
     * (vezir server >= 0.14.2: ``POST /api/sessions/{id}/auto-label``).
     * Matches speakers against the team's voiceprint DB and applies
     * confident names; unrecognized speakers stay raw and the session
     * remains needs_labeling.  Explicit consent: overrides an upload-time
     * auto-label opt-out.  When [sync] is true and every speaker is
     * resolved, the session is also pushed to the team git repo.
     */
    suspend fun autoLabel(
        sessionId: String,
        sync: Boolean = false,
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val obj = JSONObject()
            if (sync) obj.put("sync", true)
            val body = obj.toString().toRequestBody("application/json".toMediaType())
            val req = HttpClients.authHeaders(
                Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId/auto-label"),
                token, teamId,
            ).post(body).build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.Ok(true)
                    else Result.HttpError(resp.code, resp.message)
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    /**
     * Add, change, or clear a session's title after recording (vezir server
     * >= 0.12.0: ``POST /api/sessions/{id}/title``). A blank/null [title]
     * clears it. Auth mirrors delete: server admin OR original uploader
     * (403 for other members, 404 cross-team). The response ``warning``
     * (surfaced as [Mutation.warning]) is non-null when the session was
     * already synced — the pushed folder is not renamed retroactively.
     */
    suspend fun setTitle(
        sessionId: String,
        title: String?,
    ): Result<Mutation> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("title", title ?: JSONObject.NULL)
                .toString().toRequestBody("application/json".toMediaType())
            val req = HttpClients.authHeaders(
                Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId/title"),
                token, teamId,
            ).post(body).build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val b = resp.body?.string().orEmpty()
                        Result.Ok(parseMutation(b))
                    } else {
                        Result.HttpError(resp.code, errorDetail(resp))
                    }
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    /**
     * Hard-delete a session from the server (vezir server >= 0.8.12:
     * ``DELETE /api/sessions/{id}``): DB row + on-disk artifacts. Auth
     * mirrors set-title. Push-only sync means an already-synced copy stays
     * in the team git repo; that is reported via [Mutation.warning].
     */
    suspend fun deleteSession(sessionId: String): Result<Mutation> =
        withContext(Dispatchers.IO) {
            val req = HttpClients.authHeaders(
                Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId"),
                token, teamId,
            ).delete().build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val b = resp.body?.string().orEmpty()
                        Result.Ok(parseMutation(b))
                    } else {
                        Result.HttpError(resp.code, errorDetail(resp))
                    }
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    suspend fun shareWithTeam(sessionId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val req = HttpClients.authHeaders(
                Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId/share"),
                token, teamId,
            ).post(EMPTY_JSON).build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.Ok(true)
                    else Result.HttpError(resp.code, resp.message)
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    /** An artifact download: bytes plus the display filename. */
    data class ArtifactDownload(val filename: String, val bytes: ByteArray)

    suspend fun downloadArtifact(
        sessionId: String,
        name: String,
    ): Result<ArtifactDownload> = withContext(Dispatchers.IO) {
        val req = HttpClients.authHeaders(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/artifact/$sessionId/$name"),
            token, teamId,
        ).get().build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    val filename = filenameFromDisposition(
                        resp.header("Content-Disposition"),
                    ) ?: name
                    Result.Ok(ArtifactDownload(filename, bytes))
                } else {
                    Result.HttpError(resp.code, resp.message)
                }
            }
        }.getOrElse { e ->
            if (e is IOException) Result.NetworkError(e) else throw e
        }
    }

    suspend fun getTeam(): Result<List<String>> = withContext(Dispatchers.IO) {
        val req = HttpClients.authHeaders(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/team"),
            token, teamId,
        ).get().build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use Result.HttpError(
                        resp.code, "empty body",
                    )
                    @Serializable data class TeamResp(val team: List<String>)
                    Result.Ok(json.decodeFromString(TeamResp.serializer(), body).team)
                } else {
                    Result.HttpError(resp.code, resp.message)
                }
            }
        }.getOrElse { e ->
            if (e is IOException) Result.NetworkError(e) else throw e
        }
    }
}
