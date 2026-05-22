package com.vezir.android.net

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Plays audio clips from the vezir labeling endpoint.
 *
 * Uses OkHttp to fetch the WAV (needs bearer auth + custom CA trust
 * that Android's MediaPlayer.setDataSource(url) cannot provide), writes
 * to a temp file, then plays via MediaPlayer.
 *
 * Only one clip plays at a time. Calling [play] while another clip is
 * playing stops the current one first.
 */
class AudioClipPlayer(
    private val token: String,
    caPem: String? = null,
    private val cacheDir: File,
) {
    companion object {
        private const val TAG = "AudioClipPlayer"
    }

    private val client: OkHttpClient =
        (caPem?.let { CaTrustManager.builderWithCa(it) } ?: OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    /** The speaker ID of the currently playing clip, or null. */
    var currentSpeakerId: String? = null
        private set

    val isPlaying: Boolean get() = player?.isPlaying == true

    /**
     * Fetch and play a clip from [url]. Stops any currently playing clip.
     *
     * Runs the network fetch on IO dispatcher; playback starts on the
     * calling thread's looper (must be main thread for MediaPlayer).
     *
     * @param speakerId used to track which speaker is playing (UI state)
     * @param onComplete called when playback finishes naturally
     * @param onError called on fetch or playback errors
     */
    suspend fun play(
        url: String,
        speakerId: String,
        onComplete: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        stop()
        currentSpeakerId = speakerId

        // Fetch the WAV to a temp file (needs auth headers).
        val file = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    val msg = when (resp.code) {
                        404 -> "audio not available (may have been deleted)"
                        429 -> "rate limited; try again in a moment"
                        else -> "HTTP ${resp.code}"
                    }
                    resp.close()
                    onError(msg)
                    return@withContext null
                }
                val tmp = File.createTempFile("clip_${speakerId}_", ".wav", cacheDir)
                resp.body?.byteStream()?.use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                resp.close()
                tmp
            } catch (e: IOException) {
                Log.w(TAG, "clip fetch failed: ${e.message}")
                onError("network error: ${e.message}")
                null
            }
        } ?: return

        currentFile = file

        // Play on main thread.
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    currentSpeakerId = null
                    onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error: what=$what extra=$extra")
                    currentSpeakerId = null
                    onError("playback error")
                    true
                }
                start()
            }
            player = mp
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer setup failed: ${e.message}")
            currentSpeakerId = null
            onError("playback error: ${e.message}")
        }
    }

    /** Stop playback and clean up temp file. */
    fun stop() {
        try {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        player = null
        currentSpeakerId = null
        currentFile?.delete()
        currentFile = null
    }

    /** Release all resources. Call from onDispose / onCleared. */
    fun release() {
        stop()
    }
}
