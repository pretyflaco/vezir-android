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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API client for session list, detail, sync-now, share, and artifact
 * download. Mirrors the server endpoints in vezir/server/sessions.py.
 */
class SessionApi(
    private val baseUrl: String,
    private val token: String,
    caPem: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val EMPTY_JSON = "{}".toRequestBody("application/json".toMediaType())
    }

    private val client: OkHttpClient =
        (caPem?.let { CaTrustManager.builderWithCa(it) } ?: OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

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
        val isTerminal: Boolean get() = status in setOf("done", "error")
    }

    @Serializable
    data class SessionList(val sessions: List<Session>)

    sealed class Result<out T> {
        data class Ok<T>(val data: T) : Result<T>()
        data class HttpError(val code: Int, val message: String) : Result<Nothing>()
        data class NetworkError(val cause: Throwable) : Result<Nothing>()
    }

    suspend fun getSessions(limit: Int = 50): Result<List<Session>> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/sessions?limit=$limit")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
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
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
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

    suspend fun syncNow(sessionId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/session/$sessionId/sync")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(EMPTY_JSON)
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.Ok(true)
                    else Result.HttpError(resp.code, resp.message)
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    suspend fun retrySummary(sessionId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId/retry-summary")
                .header("Authorization", "Bearer $token")
                .post(EMPTY_JSON)
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.Ok(true)
                    else Result.HttpError(resp.code, resp.message)
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    suspend fun shareWithTeam(sessionId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId/share")
                .header("Authorization", "Bearer $token")
                .post(EMPTY_JSON)
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.Ok(true)
                    else Result.HttpError(resp.code, resp.message)
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    suspend fun downloadArtifact(
        sessionId: String,
        name: String,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/artifact/$sessionId/$name")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.Ok(resp.body?.bytes() ?: ByteArray(0))
                } else {
                    Result.HttpError(resp.code, resp.message)
                }
            }
        }.getOrElse { e ->
            if (e is IOException) Result.NetworkError(e) else throw e
        }
    }

    suspend fun getTeam(): Result<List<String>> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/team")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
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
