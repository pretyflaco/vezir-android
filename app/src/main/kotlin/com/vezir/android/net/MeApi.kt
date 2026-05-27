package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Client for `GET /api/me` -- returns the identity associated with a
 * bearer token: GitHub handle, admin flag, and the list of every team
 * the user is a member of.
 *
 * v0.5.0 (vezir server 0.7.0): response shape changed.  The single
 * ``team_id`` / ``team_name`` fields were replaced with a
 * ``memberships`` array.  Clients pick which team to operate on by
 * sending an ``X-Team-Id`` header on subsequent requests.
 *
 * This endpoint deliberately does NOT require X-Team-Id (it's the
 * discovery surface clients use BEFORE picking a team), so MeApi
 * never sends one.
 */
class MeApi(
    private val baseUrl: String,
    private val token: String,
    caPem: String? = null,
    externalClient: OkHttpClient? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val client: OkHttpClient = externalClient
        ?: HttpClients.build(caPem, connectTimeoutSec = 5, readTimeoutSec = 5)

    @Serializable
    data class Membership(
        val team_id: String,
        val team_name: String,
        val role: String,  // 'admin' or 'scribe'
    )

    @Serializable
    data class MeResponse(
        val github: String,
        val is_admin: Boolean = false,
        val memberships: List<Membership> = emptyList(),
        val alternate_urls: List<String> = emptyList(),
    )

    suspend fun getMe(): SessionApi.Result<MeResponse> = withContext(Dispatchers.IO) {
        // No X-Team-Id: /api/me is the discovery endpoint, called before
        // the client knows which team to send.
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/me")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use SessionApi.Result.HttpError(
                        resp.code, "empty body",
                    )
                    SessionApi.Result.Ok(json.decodeFromString(MeResponse.serializer(), body))
                } else {
                    SessionApi.Result.HttpError(resp.code, resp.message)
                }
            }
        }.getOrElse { e ->
            if (e is IOException) SessionApi.Result.NetworkError(e) else throw e
        }
    }
}
