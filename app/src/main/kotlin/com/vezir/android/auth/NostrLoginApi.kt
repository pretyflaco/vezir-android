package com.vezir.android.auth

import android.util.Base64
import com.vezir.android.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Posts a NIP-55-signed NIP-98 event to vezir's nostr login endpoint and
 * returns the session JWT + identity.
 *
 * The server (`/api/auth/nostr/login`) expects
 * `Authorization: Nostr <base64(signed-event-json)>` and returns
 * `{session_jwt, github, is_admin, npub, expires_in, memberships,
 * alternate_urls}`.  The `session_jwt` is used exactly like a `vzr_`
 * token thereafter (`Authorization: Bearer`).
 */
class NostrLoginApi(
    private val baseUrl: String,
    caPem: String? = null,
    externalClient: OkHttpClient? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The login URL the NIP-98 `u` tag must bind to. */
        fun loginUrl(baseUrl: String): String =
            "${baseUrl.trimEnd('/')}/api/auth/nostr/login"
    }

    private val client: OkHttpClient = externalClient
        ?: HttpClients.build(
            caPem, connectTimeoutSec = 15, readTimeoutSec = 30, refreshOn401 = false,
        )

    @Serializable
    data class LoginResponse(
        val session_jwt: String,
        val github: String,
        val is_admin: Boolean = false,
        val npub: String = "",
        val expires_in: Long = 0,
        // Rotating refresh-token session (vezir server >= 0.10.0).  Absent
        // (empty) against older servers, in which case the app falls back
        // to the pre-refresh re-login-on-401 behaviour.
        val access_jwt: String = "",
        val refresh_token: String = "",
        val refresh_expires_in: Long = 0,
    )

    sealed class Result {
        data class Ok(val data: LoginResponse) : Result()
        data class HttpError(val code: Int, val message: String) : Result()
        data class NetworkError(val cause: IOException) : Result()
    }

    /** POST the base64-encoded signed event; parse the JWT response. */
    suspend fun login(signedEventJson: String): Result = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(
            signedEventJson.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val req = Request.Builder()
            .url(loginUrl(baseUrl))
            .header("Authorization", "Nostr $b64")
            // Body is unused by the server (auth is in the header) but POST
            // needs a body; send an empty JSON object.
            .post("{}".toRequestBody(null))
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    Result.Ok(json.decodeFromString(LoginResponse.serializer(), body))
                } else {
                    Result.HttpError(resp.code, errorDetail(body, resp.message))
                }
            }
        }.getOrElse { e ->
            if (e is IOException) Result.NetworkError(e) else throw e
        }
    }

    private fun errorDetail(body: String, fallback: String): String =
        try {
            kotlinx.serialization.json.Json
                .parseToJsonElement(body)
                .let { (it as? kotlinx.serialization.json.JsonObject)?.get("detail")?.toString() }
                ?.trim('"')
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
}
