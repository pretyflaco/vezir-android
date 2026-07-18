package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Exchanges a rotating refresh token for a fresh access/refresh pair via
 * `POST /api/auth/refresh` (vezir server >= 0.10.0).
 *
 * The server returns
 * `{access_jwt, session_jwt, refresh_token, expires_in, refresh_expires_in,
 *   sid}`; `access_jwt` and `session_jwt` are the same short-lived access
 * token (`session_jwt` kept for pre-refresh clients).  The refresh token
 * is single-use and rotates on every call, so the caller MUST persist the
 * returned [Pair.refreshToken].
 *
 * A 401 means the refresh token is unusable (unknown, idle/absolute
 * expired, revoked, or a reuse that revoked the family) — the caller must
 * fall back to a full re-login.
 */
class RefreshApi(
    private val baseUrl: String,
    caPem: String? = null,
    externalClient: OkHttpClient? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Backoff before the single lost-response retry (grace-window safe). */
        private const val NETWORK_RETRY_DELAY_MS = 750L
    }

    // Deliberately a plain client (no auth Authenticator): the refresh
    // endpoint authenticates with the refresh token in the body, and we
    // must never recurse into refresh-on-401 from within a refresh.
    private val client: OkHttpClient = externalClient
        ?: HttpClients.build(
            caPem, connectTimeoutSec = 15, readTimeoutSec = 30, refreshOn401 = false,
        )

    @Serializable
    data class RefreshResponse(
        val access_jwt: String = "",
        val session_jwt: String = "",
        val refresh_token: String = "",
        val expires_in: Long = 0,
        val refresh_expires_in: Long = 0,
    ) {
        /** The access token, preferring `access_jwt` with `session_jwt` fallback. */
        fun accessToken(): String = access_jwt.ifEmpty { session_jwt }
    }

    sealed class Result {
        data class Ok(val data: RefreshResponse) : Result()
        data class HttpError(val code: Int, val message: String) : Result()
        data class NetworkError(val cause: IOException) : Result()
    }

    /**
     * Exchange [refreshToken] for a fresh pair.
     *
     * Retries once on a *network* error.  vezir server >= 0.11.0 keeps a
     * refresh **grace window** (``VEZIR_REFRESH_GRACE``, default 60s): a
     * refresh token that was just consumed can be replayed within the
     * window to re-issue the same pair, precisely so a client that lost the
     * response to a network hiccup can retry safely (RFC 9700 lost-response
     * handling).  We therefore retry a [Result.NetworkError] once.
     *
     * A [Result.HttpError] (notably 401) is NOT retried: a 401 means the
     * token was rejected — replaying it would trip reuse detection and
     * revoke the whole session family.  The caller must re-login.
     */
    suspend fun refresh(refreshToken: String): Result = withContext(Dispatchers.IO) {
        var last: Result = attempt(refreshToken)
        if (last is Result.NetworkError) {
            // Lost-response retry; safe inside the server grace window.
            delay(NETWORK_RETRY_DELAY_MS)
            last = attempt(refreshToken)
        }
        last
    }

    private fun attempt(refreshToken: String): Result {
        val payload = JSONObject().put("refresh_token", refreshToken).toString()
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/auth/refresh")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    Result.Ok(json.decodeFromString(RefreshResponse.serializer(), body))
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
            JSONObject(body).optString("detail", fallback)
        } catch (_: Exception) {
            fallback
        }
}
