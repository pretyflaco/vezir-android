package com.vezir.android.auth

import com.vezir.android.net.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException

/**
 * Drives vezir's Google sign-in (OAuth 2.0 Device Authorization Grant).
 * The server proxies Google's device + token endpoints (the client secret
 * never leaves the server), so the app only talks to vezir:
 *
 *   1. GET  /api/auth/google/config        → is Google enabled?
 *   2. POST /api/auth/google/device/start  → user_code + verification_url + device_code
 *   3. (user approves in a browser as their @blinkbtc.com account)
 *   4. POST /api/auth/google/device/poll   → 200 JWT | 202 pending | terminal
 *
 * On success returns the same `{session_jwt, github, is_admin, email, …}`
 * shape as the nostr path; the JWT is used as a `Bearer` token thereafter.
 */
class GoogleLoginApi(
    private val baseUrl: String,
    caPem: String? = null,
    externalClient: OkHttpClient? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val client: OkHttpClient = externalClient
        ?: HttpClients.build(
            caPem, connectTimeoutSec = 15, readTimeoutSec = 30, refreshOn401 = false,
        )

    private fun base() = baseUrl.trimEnd('/')

    @Serializable
    data class Config(
        val configured: Boolean = false,
        val client_id: String? = null,
        val allowed_domain: String? = null,
    )

    @Serializable
    data class DeviceStart(
        val device_code: String,
        val user_code: String,
        val verification_url: String? = null,
        // URL with the user_code embedded (server 0.8.1+), so we can open a
        // pre-filled verification page — no manual code typing.
        val verification_url_complete: String? = null,
        val expires_in: Long? = null,
        val interval: Int = 5,
        val allowed_domain: String? = null,
    )

    @Serializable
    data class LoginResponse(
        val session_jwt: String,
        val github: String,
        val is_admin: Boolean = false,
        val email: String = "",
        val expires_in: Long = 0,
        // Rotating refresh-token session (vezir server >= 0.10.0).  Absent
        // (empty) against older servers.
        val access_jwt: String = "",
        val refresh_token: String = "",
        val refresh_expires_in: Long = 0,
    )

    sealed class Result<out T> {
        data class Ok<T>(val data: T) : Result<T>()
        data class HttpError(val code: Int, val message: String) : Result<Nothing>()
        data class NetworkError(val cause: IOException) : Result<Nothing>()
    }

    suspend fun fetchConfig(): Result<Config> = withContext(Dispatchers.IO) {
        get("/api/auth/google/config") { json.decodeFromString(Config.serializer(), it) }
    }

    suspend fun deviceStart(): Result<DeviceStart> = withContext(Dispatchers.IO) {
        post("/api/auth/google/device/start", body = "{}") {
            json.decodeFromString(DeviceStart.serializer(), it)
        }
    }

    /** One poll. 200 → Ok(login), 202 → Pending, else terminal HttpError. */
    sealed class PollResult {
        data class Done(val data: LoginResponse) : PollResult()
        object Pending : PollResult()
        data class Failed(val code: Int, val message: String) : PollResult()
    }

    suspend fun devicePollOnce(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("device_code", deviceCode).toString()
        val req = Request.Builder()
            .url(base() + "/api/auth/google/device/poll")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> PollResult.Done(json.decodeFromString(LoginResponse.serializer(), body))
                    202 -> PollResult.Pending
                    else -> PollResult.Failed(resp.code, errorDetail(body, resp.message))
                }
            }
        } catch (e: IOException) {
            // Treat a transient network blip as pending so the loop retries.
            PollResult.Pending
        }
    }

    /**
     * Convenience: poll [deviceCode] every [intervalSec] until done, a
     * terminal failure, or [timeoutSec] elapses.  [onPending] lets the UI
     * stay responsive (e.g. a tick).
     */
    suspend fun pollUntilDone(
        deviceCode: String,
        intervalSec: Int,
        timeoutSec: Int = 300,
        onPending: () -> Unit = {},
    ): Result<LoginResponse> = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var interval = intervalSec.coerceAtLeast(1)
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            when (val r = devicePollOnce(deviceCode)) {
                is PollResult.Done -> return@withContext Result.Ok(r.data)
                is PollResult.Failed -> return@withContext Result.HttpError(r.code, r.message)
                PollResult.Pending -> onPending()
            }
        }
        Result.HttpError(408, "timed out waiting for Google approval")
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private inline fun <T> get(path: String, parse: (String) -> T): Result<T> {
        val req = Request.Builder().url(base() + path).get().build()
        return execute(req, parse)
    }

    private inline fun <T> post(path: String, body: String, parse: (String) -> T): Result<T> {
        val req = Request.Builder()
            .url(base() + path)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        return execute(req, parse)
    }

    private inline fun <T> execute(req: Request, parse: (String) -> T): Result<T> =
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.Ok(parse(body))
                else Result.HttpError(resp.code, errorDetail(body, resp.message))
            }
        } catch (e: IOException) {
            Result.NetworkError(e)
        }

    private fun errorDetail(body: String, fallback: String): String =
        try {
            JSONObject(body).optString("detail", fallback)
        } catch (_: Exception) {
            fallback
        }
}
