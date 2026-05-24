package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API client for the native speaker-labeling endpoints:
 *
 *   GET  /api/label/{sessionId}  → speaker list + team handles
 *   POST /api/label/{sessionId}  → apply labels
 *   GET  /label/{sessionId}/clip/{speakerId}  → audio clip URL (for AudioClipPlayer)
 */
class LabelApi(
    private val baseUrl: String,
    private val token: String,
    caPem: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val client: OkHttpClient =
        (caPem?.let { CaTrustManager.builderWithCa(it) } ?: OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    @Serializable
    data class Speaker(
        val id: String,
        val channel: String? = null,   // millet (formerly meetscribe) uses "mic"/"system", not int
        val sample_text: String? = null,
    )

    @Serializable
    data class LabelData(
        val session_id: String,
        val status: String,
        val speakers: List<Speaker>,
        val team: List<String>,
        val audio_available: Boolean = false,
    )

    sealed class Result<out T> {
        data class Ok<T>(val data: T) : Result<T>()
        data class HttpError(val code: Int, val message: String) : Result<Nothing>()
        data class NetworkError(val cause: Throwable) : Result<Nothing>()
    }

    /** Fetch the speaker list and team handles for a session. */
    suspend fun getSpeakers(sessionId: String): Result<LabelData> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/label/$sessionId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use Result.HttpError(
                            resp.code, "empty response body",
                        )
                        Result.Ok(json.decodeFromString(LabelData.serializer(), body))
                    } else {
                        Result.HttpError(resp.code, resp.message)
                    }
                }
            }.getOrElse { e ->
                if (e is IOException) Result.NetworkError(e) else throw e
            }
        }

    /** Submit speaker labels. Returns Ok(true) on success. */
    suspend fun submitLabels(
        sessionId: String,
        labels: Map<String, String>,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            kotlinx.serialization.serializer<Map<String, Map<String, String>>>(),
            mapOf("labels" to labels),
        )
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/label/$sessionId")
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.Ok(true)
                } else {
                    Result.HttpError(resp.code, resp.message)
                }
            }
        }.getOrElse { e ->
            if (e is IOException) Result.NetworkError(e) else throw e
        }
    }

    /** Build the URL for streaming a speaker's audio clip. */
    fun clipUrl(sessionId: String, speakerId: String): String =
        "${baseUrl.trimEnd('/')}/label/$sessionId/clip/$speakerId"
}
