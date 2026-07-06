package com.vezir.android.net

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Resumable upload client implementing vezir's tus.io 1.0 subset
 * (server: vezir/server/uploads.py, v0.7.3+):
 *
 *   POST   /upload/resumable        -> create session (Upload-Length header)
 *   HEAD   /upload/resumable/{id}   -> current Upload-Offset
 *   PATCH  /upload/resumable/{id}   -> append at Upload-Offset
 *
 * On a mid-upload failure it HEADs the session to learn the server's
 * offset and resumes the PATCH from there instead of restarting at byte
 * 0 (the gap the old [Uploader] had).  Streams chunks from a content
 * URI, seeking to the resume offset.
 *
 * v0.8.0:
 *  * [tokenProvider] instead of a frozen token — after the OkHttp
 *    authenticator rotates the session mid-upload, every subsequent
 *    chunk immediately carries the fresh token instead of paying a
 *    401 + refresh-mutex round-trip per request.
 *  * Per-chunk read/write timeouts (a chunk is only 4 MiB) — the old
 *    30-minute socket timeouts could leave a dead-but-open connection
 *    "in progress" for hours.
 *  * [upload] can resume a persisted upload session (`existingUploadId`)
 *    so a process-death mid-upload no longer restarts from byte 0.
 */
class ResumableUploader(
    private val baseUrl: String,
    private val tokenProvider: () -> String,
    private val teamId: String?,
    private val contentResolver: ContentResolver,
    caPem: String? = null,
) {
    companion object {
        private const val TAG = "VezirResumable"
        private const val TUS_VERSION = "1.0.0"
        private const val CHUNK = 4 * 1024 * 1024 // 4 MiB
        private val OFFSET_OCTET = "application/offset+octet-stream".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
        // Support probe result per base URL: the answer can't change
        // without a server upgrade, so don't re-probe (and re-create a
        // junk zero-length session) before every upload.
        private val supportCache =
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    }

    @Serializable
    data class CreateResponse(val upload_id: String, val offset: Long = 0)

    @Serializable
    data class CompleteResponse(val session_id: String, val bytes: Long)

    sealed class Outcome {
        data class Success(val sessionId: String, val bytes: Long) : Outcome()
        data class HttpError(val code: Int, val message: String, val body: String?) : Outcome()
        data class Failed(val cause: Throwable) : Outcome()
        /** Server doesn't expose the resumable endpoints; caller should fall back. */
        object Unsupported : Outcome()
    }

    fun interface Progress { fun onProgress(sent: Long, total: Long) }
    fun interface OnRetry { fun onRetry(attempt: Int, max: Int, cause: Throwable) }

    private val client: OkHttpClient =
        HttpClients.build(caPem, connectTimeoutSec = 30, readTimeoutSec = 2 * 60)
            .newBuilder()
            // Per-CHUNK budget: 4 MiB must finish in 5 min or the
            // connection is dead; the retry loop then resumes from the
            // server's offset.
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()

    /** Probe whether the server exposes resumable endpoints (cached). */
    suspend fun isSupported(): Boolean = withContext(Dispatchers.IO) {
        supportCache[baseUrl]?.let { return@withContext it }
        val url = baseUrl.trimEnd('/') + "/upload/resumable"
        // Upload-Length=0 => 400 if the route exists, 404/405 if not.
        val req = HttpClients.authHeaders(Request.Builder().url(url), tokenProvider(), teamId)
            .addHeader("Upload-Length", "0")
            .post(RequestBody.create(null, ByteArray(0)))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val supported = resp.code != 404 && resp.code != 405
                supportCache[baseUrl] = supported
                supported
            }
        } catch (_: IOException) {
            // Flaky network is NOT evidence the route is missing.  Assume
            // supported — exactly when the network is bad is when resume
            // matters most, and upload() itself falls back on a real
            // 404/405 (Outcome.Unsupported).  Don't cache the guess.
            true
        }
    }

    suspend fun upload(
        contentUri: Uri,
        fileName: String,
        title: String?,
        summaryPreset: String? = null,
        autoLabel: Boolean = true,
        sync: Boolean = true,
        personal: Boolean = false,
        maxAttempts: Int = 5,
        progress: Progress = Progress { _, _ -> },
        onRetry: OnRetry = OnRetry { _, _, _ -> },
        // v0.8.0: resume a persisted upload session across process death.
        // When set, the id is HEADed first; if the server still knows it,
        // the PATCH resumes from the server's offset instead of byte 0.
        // A stale/unknown id falls through to creating a fresh session.
        existingUploadId: String? = null,
        // Fired as soon as a session id is known (created or reused) so
        // the caller can persist it BEFORE any bytes move.
        onSession: (String) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val total = queryUriSize(contentUri)
        if (total <= 0L) {
            return@withContext Outcome.Failed(IOException("unknown upload size for $contentUri"))
        }
        val base = baseUrl.trimEnd('/')

        var uploadId: String? = null
        var initialOffset = 0L
        if (existingUploadId != null) {
            try {
                val off = headOffset("$base/upload/resumable/$existingUploadId")
                if (off != null) {
                    uploadId = existingUploadId
                    initialOffset = off
                    Log.i(TAG, "resuming persisted upload $existingUploadId from offset $off")
                }
            } catch (_: IOException) {
                // Transient probe failure: prefer a fresh session over
                // failing the whole upload here.
            }
        }

        if (uploadId == null) {
            // 1. Create the session.
            val createReq = HttpClients.authHeaders(
                Request.Builder().url("$base/upload/resumable"), tokenProvider(), teamId,
            )
                .addHeader("Upload-Length", total.toString())
                .addHeader("Upload-Filename", fileName)
                .addHeader("Upload-Content-Type", "audio/ogg")
                .post(formBody(title, summaryPreset, autoLabel, sync, personal))
                .build()

            try {
                client.newCall(createReq).execute().use { resp ->
                    val b = resp.body?.string()
                    if (resp.code == 404 || resp.code == 405) {
                        supportCache[baseUrl] = false
                        return@withContext Outcome.Unsupported
                    }
                    if (!resp.isSuccessful || b == null) {
                        return@withContext Outcome.HttpError(resp.code, resp.message, b)
                    }
                    uploadId = json.decodeFromString(CreateResponse.serializer(), b).upload_id
                }
            } catch (e: IOException) {
                return@withContext Outcome.Failed(e)
            }
        }

        onSession(uploadId!!)
        val location = "$base/upload/resumable/$uploadId"
        var offset = initialOffset
        var lastCause: Throwable? = null

        for (attempt in 1..maxAttempts) {
            try {
                // Re-sync offset from server on a retry.
                if (attempt > 1) {
                    offset = headOffset(location) ?: offset
                }
                val result = patchFrom(
                    location, contentUri, offset, total, progress,
                )
                when (result) {
                    is PatchResult.Complete ->
                        return@withContext Outcome.Success(result.sessionId, result.bytes)
                    is PatchResult.Advanced -> {
                        offset = result.offset
                        // Loop completed without a 200 — treat as transient.
                        lastCause = IOException("upload incomplete at offset $offset")
                    }
                    is PatchResult.Http ->
                        return@withContext Outcome.HttpError(result.code, result.message, result.body)
                }
            } catch (e: IOException) {
                lastCause = e
            } catch (e: Throwable) {
                return@withContext Outcome.Failed(e)
            }

            if (attempt < maxAttempts && lastCause != null) {
                onRetry.onRetry(attempt, maxAttempts, lastCause!!)
                val backoffMs = 1000L * (1L shl (attempt - 1))
                Log.w(TAG, "resumable attempt $attempt/$maxAttempts failed: $lastCause; resuming from $offset in ${backoffMs}ms")
                delay(backoffMs)
            }
        }
        Outcome.Failed(lastCause ?: IOException("resumable upload failed"))
    }

    private sealed class PatchResult {
        data class Complete(val sessionId: String, val bytes: Long) : PatchResult()
        data class Advanced(val offset: Long) : PatchResult()
        data class Http(val code: Int, val message: String, val body: String?) : PatchResult()
    }

    /** PATCH chunks starting at [startOffset] until complete or error. */
    private fun patchFrom(
        location: String,
        contentUri: Uri,
        startOffset: Long,
        total: Long,
        progress: Progress,
    ): PatchResult {
        var offset = startOffset
        while (offset < total) {
            val end = minOf(offset + CHUNK, total)
            val body = SlicedUriBody(contentResolver, contentUri, offset, end - offset, OFFSET_OCTET)
            val req = HttpClients.authHeaders(Request.Builder().url(location), tokenProvider(), teamId)
                .addHeader("Upload-Offset", offset.toString())
                .addHeader("Tus-Resumable", TUS_VERSION)
                .patch(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val b = resp.body?.string()
                when {
                    resp.code == 409 -> {
                        // Offset drift: re-sync from the header and retry loop.
                        offset = resp.header("Upload-Offset")?.toLongOrNull() ?: offset
                        return@use
                    }
                    resp.code in 500..599 ->
                        throw IOException("HTTP ${resp.code} ${resp.message}")
                    !resp.isSuccessful ->
                        return PatchResult.Http(resp.code, resp.message, b)
                    resp.code == 200 && b != null -> {
                        val parsed = json.decodeFromString(CompleteResponse.serializer(), b)
                        progress.onProgress(total, total)
                        return PatchResult.Complete(parsed.session_id, parsed.bytes)
                    }
                    else -> {
                        offset = resp.header("Upload-Offset")?.toLongOrNull() ?: end
                        progress.onProgress(offset, total)
                    }
                }
            }
        }
        return PatchResult.Advanced(offset)
    }

    private fun headOffset(location: String): Long? {
        val req = HttpClients.authHeaders(Request.Builder().url(location), tokenProvider(), teamId)
            .head()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.header("Upload-Offset")?.toLongOrNull()
        }
    }

    private fun formBody(
        title: String?,
        summaryPreset: String?,
        autoLabel: Boolean,
        sync: Boolean,
        personal: Boolean,
    ): RequestBody {
        val b = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
        if (!title.isNullOrBlank()) b.addFormDataPart("title", title)
        if (!summaryPreset.isNullOrBlank()) b.addFormDataPart("summary_preset", summaryPreset)
        b.addFormDataPart("auto_label", if (autoLabel) "true" else "false")
        b.addFormDataPart("sync", if (sync) "true" else "false")
        b.addFormDataPart("personal", if (personal) "true" else "false")
        return b.build()
    }

    private fun queryUriSize(uri: Uri): Long =
        try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) {
            -1L
        }
}

/**
 * OkHttp [RequestBody] streaming a byte-range slice [start, start+length)
 * of a content URI.  Used so a resumed PATCH can skip to the server's
 * current offset without re-sending earlier bytes.
 */
private class SlicedUriBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val start: Long,
    private val length: Long,
    private val mediaType: okhttp3.MediaType,
) : RequestBody() {
    override fun contentType() = mediaType
    override fun contentLength() = length
    override fun writeTo(sink: BufferedSink) {
        val stream = resolver.openInputStream(uri)
            ?: throw IOException("openInputStream null for $uri")
        stream.use { input ->
            var toSkip = start
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) throw IOException("could not seek to offset $start")
                toSkip -= skipped
            }
            var remaining = length
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                sink.write(buf, 0, n)
                remaining -= n
            }
        }
    }
}
