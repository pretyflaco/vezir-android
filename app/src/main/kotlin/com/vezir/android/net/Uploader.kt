package com.vezir.android.net

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Uploads an OGG to vezir's POST /upload, mirroring the Linux client's
 * behaviour at vezir/client/uploader.py:
 *
 *   - multipart/form-data with `audio` (the file) and optional `title`
 *   - Authorization: Bearer <token>
 *   - 3 attempts with exponential backoff on connection errors and 5xx
 *   - each retry restarts upload from byte 0 (server keeps that contract;
 *     we surface it explicitly in retry messages)
 *
 * Streams from a content URI so MediaStore-backed recordings work without
 * copying through a temp file.
 */
class Uploader(
    private val baseUrl: String,
    private val token: String,
    private val contentResolver: ContentResolver,
) {
    companion object {
        private const val TAG = "VezirUploader"
        private val OGG = "audio/ogg".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    data class UploadResponse(
        val session_id: String,
        val bytes: Long,
        val dashboard_url: String,
        val dashboard_login_url: String,
    )

    sealed class Outcome {
        data class Success(val response: UploadResponse) : Outcome()
        data class HttpError(val code: Int, val message: String, val body: String?) : Outcome()
        data class Failed(val cause: Throwable) : Outcome()
    }

    /** Progress callback: bytesSent of bytesTotal, monotonic since start. */
    fun interface Progress { fun onProgress(sent: Long, total: Long) }

    /** Per-retry callback: attempt index (1..max), max, and the cause. */
    fun interface OnRetry { fun onRetry(attempt: Int, max: Int, cause: Throwable) }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Long readTimeout so we don't kill an upload mid-flight on a
        // sluggish Tailscale path; 30 minutes covers a 3h recording at
        // any reasonable network speed.
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .build()

    /**
     * @param contentUri MediaStore or file:// URI of the OGG to send
     * @param fileName display name for the multipart filename field
     * @param title optional meeting title (server forwards to the queue)
     * @param maxAttempts total tries including the first
     * @param progress progress callback fired ~every chunk
     * @param onRetry callback fired before each retry sleep
     */
    suspend fun upload(
        contentUri: Uri,
        fileName: String,
        title: String?,
        maxAttempts: Int = 3,
        progress: Progress = Progress { _, _ -> },
        onRetry: OnRetry = OnRetry { _, _, _ -> },
    ): Outcome = withContext(Dispatchers.IO) {
        val totalBytes = queryUriSize(contentUri)
        val url = baseUrl.trimEnd('/') + "/upload"

        var lastCause: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val body = buildBody(contentUri, fileName, title, totalBytes, progress)
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { resp ->
                    val responseBody = resp.body?.string()
                    if (resp.isSuccessful && responseBody != null) {
                        val parsed = runCatching {
                            json.decodeFromString(UploadResponse.serializer(), responseBody)
                        }.getOrElse {
                            return@withContext Outcome.HttpError(
                                resp.code, "OK but unparsable response", responseBody,
                            )
                        }
                        return@withContext Outcome.Success(parsed)
                    }
                    if (resp.code in 500..599) {
                        // 5xx: retry. Treat as a transient failure.
                        lastCause = IOException("HTTP ${resp.code} ${resp.message}")
                    } else {
                        // 4xx: don't retry; this is a contract failure
                        // (auth, payload too large, etc.).
                        return@withContext Outcome.HttpError(
                            resp.code, resp.message, responseBody,
                        )
                    }
                }
            } catch (e: IOException) {
                lastCause = e
            } catch (e: Throwable) {
                // Anything not IOException is probably a programmer error;
                // surface it directly without retries.
                return@withContext Outcome.Failed(e)
            }

            if (attempt < maxAttempts && lastCause != null) {
                onRetry.onRetry(attempt, maxAttempts, lastCause!!)
                val backoffMs = 1000L * (1L shl (attempt - 1))
                Log.w(TAG, "upload attempt $attempt/$maxAttempts failed: $lastCause; sleeping ${backoffMs}ms")
                delay(backoffMs)
            }
        }
        Outcome.Failed(lastCause ?: IOException("upload failed (no cause)"))
    }

    private fun buildBody(
        contentUri: Uri,
        fileName: String,
        title: String?,
        totalBytes: Long,
        progress: Progress,
    ): RequestBody {
        val fileBody = ContentUriRequestBody(contentResolver, contentUri, totalBytes, OGG, progress)
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", fileName, fileBody)
        if (!title.isNullOrBlank()) {
            builder.addFormDataPart("title", title)
        }
        return builder.build()
    }

    private fun queryUriSize(uri: Uri): Long {
        // Try the openAssetFileDescriptor path first for content URIs;
        // fall back to opening the stream and -1 if size is unknown.
        return try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }
}

/**
 * OkHttp [RequestBody] that streams from a content URI and reports
 * progress on every write. We don't know contentLength for some content
 * URIs, in which case OkHttp falls back to chunked transfer encoding.
 */
private class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val total: Long,
    private val mediaType: okhttp3.MediaType,
    private val progress: Uploader.Progress,
) : RequestBody() {
    override fun contentType() = mediaType
    override fun contentLength() = total
    override fun writeTo(sink: BufferedSink) {
        val stream = resolver.openInputStream(uri)
            ?: throw IOException("openInputStream null for $uri")
        var sent = 0L
        val started = SystemClock.elapsedRealtime()
        var lastReport = started
        stream.use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                sent += n
                val now = SystemClock.elapsedRealtime()
                // Throttle progress updates to ~10 Hz so the UI doesn't
                // get hammered on fast networks.
                if (now - lastReport >= 100L || sent == total) {
                    progress.onProgress(sent, total)
                    lastReport = now
                }
            }
            progress.onProgress(sent, total)
        }
    }
}
