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
    // v0.8.0: provider instead of a frozen token, so a mid-upload session
    // rotation is picked up by the next attempt without an extra 401.
    private val tokenProvider: () -> String,
    private val teamId: String?,
    private val contentResolver: ContentResolver,
    caPem: String? = null,
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
        // `parts` is only present on /upload/multi responses (vezir >=
        // 0.9.0): the number of audio files concatenated into the meeting.
        val parts: Int? = null,
        // v0.5.0: dashboard_url + dashboard_login_url removed from the
        // server response when running vezir 0.7.0+ (the HTML dashboard
        // is gone).  Old servers (0.6.x) still emit them; we just don't
        // read them anywhere any more.
    )

    /** One part of a multi-file meeting: its content URI + display name. */
    data class Part(val uri: Uri, val fileName: String)

    sealed class Outcome {
        data class Success(val response: UploadResponse) : Outcome()
        data class HttpError(val code: Int, val message: String, val body: String?) : Outcome()
        data class Failed(val cause: Throwable) : Outcome()
    }

    /** Progress callback: bytesSent of bytesTotal, monotonic since start. */
    fun interface Progress { fun onProgress(sent: Long, total: Long) }

    /** Per-retry callback: attempt index (1..max), max, and the cause. */
    fun interface OnRetry { fun onRetry(attempt: Int, max: Int, cause: Throwable) }

    private val client: OkHttpClient =
        HttpClients.build(caPem, connectTimeoutSec = 30, readTimeoutSec = 30 * 60)
            .newBuilder()
            // Long write timeout for large uploads over slow paths.
            .writeTimeout(30, TimeUnit.MINUTES)
            .build()

    /**
     * @param contentUri MediaStore or file:// URI of the OGG to send
     * @param fileName display name for the multipart filename field
     * @param title optional meeting title (server forwards to the queue)
     * @param summaryPreset optional summarization preset id; one of
     *        "high-quality", "confidential", "alternative".  When set, the
     *        server runs the matching backend (claudemax / Tinfoil TEE /
     *        OpenRouter) and refuses to silently fall back.  When null the
     *        server uses its configured default backend.
     * @param autoLabel whether the server should run `meet label --auto`
     *        against the central voiceprint DB.  When false the session
     *        always goes through manual labeling.  Default true.
     * @param sync whether the server should push the artifacts to the
     *        configured destination repo after the pipeline completes.
     *        When false the session reaches `done (local-only)` and the
     *        user can trigger a retroactive sync from the dashboard.
     *        Default true.
     * @param maxAttempts total tries including the first
     * @param progress progress callback fired ~every chunk
     * @param onRetry callback fired before each retry sleep
     */
    suspend fun upload(
        contentUri: Uri,
        fileName: String,
        title: String?,
        summaryPreset: String? = null,
        autoLabel: Boolean = true,
        sync: Boolean = true,
        personal: Boolean = false,
        maxAttempts: Int = 3,
        progress: Progress = Progress { _, _ -> },
        onRetry: OnRetry = OnRetry { _, _, _ -> },
    ): Outcome = withContext(Dispatchers.IO) {
        val totalBytes = queryUriSize(contentUri)
        val url = baseUrl.trimEnd('/') + "/upload"

        var lastCause: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val body = buildBody(
                    contentUri, fileName, title, summaryPreset,
                    autoLabel, sync, personal, totalBytes, progress,
                )
                val request = HttpClients.authHeaders(
                    Request.Builder().url(url), tokenProvider(), teamId,
                ).post(body).build()
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

    /**
     * Upload multiple audio files as ONE meeting via POST /upload/multi
     * (vezir server >= 0.9.0). Mirrors vezir/client/uploader.py:upload_multi:
     * the server preserves part order and the worker concatenates them
     * before transcription.
     *
     * All parts must be the same audio type (here always OGG, since the
     * import pipeline transcodes to OGG). A 404/405 response means the
     * server predates 0.9.0; surfaced as an [Outcome.HttpError] so the
     * caller can show a "server too old" message and fall back to
     * per-file single uploads.
     *
     * @param parts ordered list of audio parts (URI + display name)
     */
    suspend fun uploadMulti(
        parts: List<Part>,
        title: String?,
        summaryPreset: String? = null,
        autoLabel: Boolean = true,
        sync: Boolean = true,
        personal: Boolean = false,
        maxAttempts: Int = 3,
        progress: Progress = Progress { _, _ -> },
        onRetry: OnRetry = OnRetry { _, _, _ -> },
    ): Outcome = withContext(Dispatchers.IO) {
        require(parts.isNotEmpty()) { "uploadMulti requires at least one part" }
        val sizes = parts.map { queryUriSize(it.uri) }
        val totalBytes = if (sizes.any { it < 0 }) -1L else sizes.sum()
        val url = baseUrl.trimEnd('/') + "/upload/multi"

        var lastCause: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                val body = buildMultiBody(
                    parts, sizes, title, summaryPreset,
                    autoLabel, sync, personal, totalBytes, progress,
                )
                val request = HttpClients.authHeaders(
                    Request.Builder().url(url), tokenProvider(), teamId,
                ).post(body).build()
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
                    if (resp.code == 404 || resp.code == 405) {
                        return@withContext Outcome.HttpError(
                            resp.code,
                            "server does not support multi-audio uploads " +
                                "(requires vezir >= 0.9.0)",
                            responseBody,
                        )
                    }
                    if (resp.code in 500..599) {
                        lastCause = IOException("HTTP ${resp.code} ${resp.message}")
                    } else {
                        return@withContext Outcome.HttpError(
                            resp.code, resp.message, responseBody,
                        )
                    }
                }
            } catch (e: IOException) {
                lastCause = e
            } catch (e: Throwable) {
                return@withContext Outcome.Failed(e)
            }

            if (attempt < maxAttempts && lastCause != null) {
                onRetry.onRetry(attempt, maxAttempts, lastCause!!)
                val backoffMs = 1000L * (1L shl (attempt - 1))
                Log.w(TAG, "multi upload attempt $attempt/$maxAttempts failed: $lastCause; sleeping ${backoffMs}ms")
                delay(backoffMs)
            }
        }
        Outcome.Failed(lastCause ?: IOException("multi upload failed (no cause)"))
    }

    private fun buildMultiBody(
        parts: List<Part>,
        sizes: List<Long>,
        title: String?,
        summaryPreset: String?,
        autoLabel: Boolean,
        sync: Boolean,
        personal: Boolean,
        totalBytes: Long,
        progress: Progress,
    ): RequestBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        // Aggregate progress across all parts so the UI sees one 0..total ramp.
        var sentBefore = 0L
        parts.forEachIndexed { i, part ->
            val partTotal = sizes[i]
            val base = sentBefore
            val partBody = ContentUriRequestBody(
                contentResolver, part.uri, partTotal, OGG,
            ) { sent, _ -> progress.onProgress(base + sent, totalBytes) }
            builder.addFormDataPart("audio", part.fileName, partBody)
            if (partTotal > 0) sentBefore += partTotal
        }
        // Server validates the declared total against bytes received.
        if (totalBytes >= 0) builder.addFormDataPart("audio_bytes", totalBytes.toString())
        if (!title.isNullOrBlank()) builder.addFormDataPart("title", title)
        if (!summaryPreset.isNullOrBlank()) {
            builder.addFormDataPart("summary_preset", summaryPreset)
        }
        builder.addFormDataPart("auto_label", if (autoLabel) "true" else "false")
        builder.addFormDataPart("sync", if (sync) "true" else "false")
        builder.addFormDataPart("personal", if (personal) "true" else "false")
        return builder.build()
    }

    private fun buildBody(
        contentUri: Uri,
        fileName: String,
        title: String?,
        summaryPreset: String?,
        autoLabel: Boolean,
        sync: Boolean,
        personal: Boolean,
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
        if (!summaryPreset.isNullOrBlank()) {
            // Server form-field name is `summary_preset` (matches the
            // FastAPI Form parameter in vezir/server/uploads.py).
            builder.addFormDataPart("summary_preset", summaryPreset)
        }
        // Always send the privacy toggles as string-encoded bools so the
        // server's _parse_bool_form() reads them consistently across
        // clients (httpx / OkHttp / curl).  Older servers ignore them
        // and default to true; that's the intended behavior for missing
        // server support.
        builder.addFormDataPart("auto_label", if (autoLabel) "true" else "false")
        builder.addFormDataPart("sync", if (sync) "true" else "false")
        builder.addFormDataPart("personal", if (personal) "true" else "false")
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
