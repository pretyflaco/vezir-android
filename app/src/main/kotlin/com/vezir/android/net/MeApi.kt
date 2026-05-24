package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for `GET /api/me` — returns the identity associated with a
 * bearer token: GitHub handle, team slug/name, admin flag.
 *
 * Used during enrollment to discover which team a token belongs to,
 * and on app launch to verify the active credential is still valid.
 */
class MeApi(
    private val baseUrl: String,
    private val token: String,
    caPem: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val client: OkHttpClient =
        (caPem?.let { CaTrustManager.builderWithCa(it) } ?: OkHttpClient.Builder())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

    @Serializable
    data class MeResponse(
        val github: String,
        val team_id: String,
        val team_name: String,
        val is_admin: Boolean = false,
    )

    suspend fun getMe(): SessionApi.Result<MeResponse> = withContext(Dispatchers.IO) {
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
