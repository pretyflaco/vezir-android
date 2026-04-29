package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tiny network surface for M1: a `/health` probe and a token-validity check
 * via `/api/sessions`.
 *
 * - `/health` is unauthenticated (per `vezir/server/app.py:34`). A 200 here
 *   confirms reachability and that the URL points at a vezir server.
 * - `/api/sessions` requires `Authorization: Bearer <token>` (per
 *   `vezir/server/sessions.py:63`). A 200 here confirms the token is valid.
 *
 * Upload, status polling, and dashboard handoff are M3.
 */
class VezirApi(private val baseUrl: String, private val token: String?) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        object Ok : Result()
        data class HttpError(val code: Int, val message: String) : Result()
        data class NetworkError(val cause: Throwable) : Result()
    }

    /** GET /health, no auth. */
    suspend fun health(): Result = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/health")
            .get()
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.Ok
                else Result.HttpError(resp.code, resp.message)
            }
        }.getOrElse { e ->
            if (e is IOException) Result.NetworkError(e) else throw e
        }
    }

    /** GET /api/sessions, bearer-auth. Used to validate the stored token. */
    suspend fun checkToken(): Result = withContext(Dispatchers.IO) {
        val tok = token ?: return@withContext Result.HttpError(401, "no token configured")
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/sessions?limit=1")
            .header("Authorization", "Bearer $tok")
            .get()
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.Ok
                else Result.HttpError(resp.code, resp.message)
            }
        }.getOrElse { e ->
            if (e is IOException) Result.NetworkError(e) else throw e
        }
    }
}
