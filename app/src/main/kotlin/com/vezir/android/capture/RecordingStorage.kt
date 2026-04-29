package com.vezir.android.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * OutputStream wrapper that exposes the running byte count. Used so the
 * UI can show file size while recording without `stat`-ing a content URI
 * each tick.
 */
class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    private val counter = AtomicLong(0L)
    val bytesWritten: Long get() = counter.get()

    override fun write(b: Int) {
        out.write(b)
        counter.incrementAndGet()
    }
    override fun write(b: ByteArray) {
        out.write(b)
        counter.addAndGet(b.size.toLong())
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        counter.addAndGet(len.toLong())
    }
}

/**
 * Picks where to put a finished recording so it shows up in the user's
 * file managers and audio apps without poking inside `Android/data/...`.
 *
 * Strategy:
 *  - On API 29+ (our minSdk) use [MediaStore.Audio.Media] with
 *    `RELATIVE_PATH=Music/Vezir/`. The OS makes the file visible
 *    everywhere (Files app, Music apps, the Media tab in any DocumentsUI
 *    file manager) and we don't need WRITE_EXTERNAL_STORAGE.
 *  - As a defensive fallback (rare: MediaStore insert failing in odd
 *    OEM ROMs), drop into `getExternalFilesDir(null)/recordings/` so
 *    we never lose audio. The UI separately surfaces the path.
 *
 * Returns a [RecordingTarget] holding the OutputStream the encoder
 * writes into, plus the content URI + display name so the UI can
 * share/upload by URI later. Caller MUST close the OutputStream.
 */
object RecordingStorage {

    private const val TAG = "VezirRecordingStorage"
    private const val MIME = "audio/ogg"
    private const val DIR = "Vezir"

    data class RecordingTarget(
        /** Counting wrapper around the real OutputStream. */
        val output: CountingOutputStream,
        /** Content URI (MediaStore) or `file://` URI (fallback path). */
        val uri: Uri,
        /** User-facing path: e.g. "Music/Vezir/vezir-2026...ogg". */
        val displayPath: String,
        /** Filename only. */
        val displayName: String,
        /**
         * MediaStore insertion is "pending" until we mark it complete; the
         * fallback path is null. Capture code MUST call [finalize] on
         * success so the file becomes visible.
         */
        private val pendingValues: PendingMediaStoreValues?,
    ) {
        /**
         * Mark the recording finished. On MediaStore-backed targets this
         * clears the pending flag so the audio appears in galleries.
         * No-op on the fallback path. Idempotent.
         */
        fun finalize(context: Context) {
            val pv = pendingValues ?: return
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, cv, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore finalize failed for $uri", e)
            }
        }

        /** Best-effort delete on failure paths. */
        fun deleteOnError(context: Context) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                // Fallback file:// uris won't have a content provider; try fs.
                if (uri.scheme == "file") {
                    runCatching { File(uri.path!!).delete() }
                } else {
                    Log.w(TAG, "delete failed for $uri", e)
                }
            }
        }
    }

    /** Marker that a target was inserted with IS_PENDING=1 and needs finalize(). */
    class PendingMediaStoreValues internal constructor()

    fun create(context: Context, displayName: String): RecordingTarget {
        // Try MediaStore first.
        try {
            val target = createViaMediaStore(context, displayName)
            if (target != null) return target
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore path unusable, falling back to app-private dir", e)
        }
        return createInAppFiles(context, displayName)
    }

    private fun createViaMediaStore(context: Context, displayName: String): RecordingTarget? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$DIR")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
        }
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore.insert returned null for $displayName")
        val out: OutputStream = resolver.openOutputStream(uri, "w")
            ?: run {
                resolver.delete(uri, null, null)
                error("openOutputStream returned null for $uri")
            }
        return RecordingTarget(
            output = CountingOutputStream(out),
            uri = uri,
            displayPath = "${Environment.DIRECTORY_MUSIC}/$DIR/$displayName",
            displayName = displayName,
            pendingValues = PendingMediaStoreValues(),
        )
    }

    private fun createInAppFiles(context: Context, displayName: String): RecordingTarget {
        val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val file = File(dir, displayName)
        return RecordingTarget(
            output = CountingOutputStream(FileOutputStream(file)),
            uri = Uri.fromFile(file),
            displayPath = "Android/data/${context.packageName}/files/recordings/$displayName",
            displayName = displayName,
            pendingValues = null,
        )
    }
}
