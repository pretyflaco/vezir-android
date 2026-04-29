package com.vezir.android.capture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vezir.android.BuildConfig
import com.vezir.android.MainActivity
import com.vezir.android.R
import com.vezir.android.audio.OpusEncoder
import com.vezir.android.audio.PcmResampler
import com.vezir.android.audio.copyClipped
import com.vezir.android.audio.mixAndClip
import com.vezir.android.audio.rmsDbfs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Foreground service that owns the capture pipeline.
 *
 * Started by the UI with EXTRA_RESULT_CODE + EXTRA_RESULT_DATA from the
 * MediaProjection consent prompt. Opens two AudioRecord streams (playback
 * via AudioPlaybackCaptureConfiguration, mic via VOICE_RECOGNITION),
 * resamples both to 16 kHz mono, sums + soft-clips, encodes Opus, and
 * mux es to OGG on disk.
 *
 * Stops on:
 *   - EXTRA_STOP_INTENT
 *   - 3h hard cap (BuildConfig.MAX_RECORDING_MILLIS)
 *   - capture-side IO failure
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "VezirCaptureService"
        private const val NOTIF_ID = 0x9E91
        private const val NOTIF_CHANNEL = "vezir-capture"

        const val ACTION_START = "com.vezir.android.capture.START"
        const val ACTION_STOP = "com.vezir.android.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TITLE = "title"

        // Capture format that the Android platform basically always supports.
        const val INPUT_SAMPLE_RATE = 48_000
        const val INPUT_CHANNELS = 2
        const val OUTPUT_SAMPLE_RATE = 16_000

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            title: String?,
        ): Intent = Intent(context, CaptureService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
        }

        fun stopIntent(context: Context): Intent = Intent(context, CaptureService::class.java).apply {
            action = ACTION_STOP
        }

        fun ensureNotificationChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
                val ch = NotificationChannel(
                    NOTIF_CHANNEL,
                    "Recording",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Persistent notification while Vezir is recording."
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private var captureThread: Thread? = null
    @Volatile private var stopRequested: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> Log.w(TAG, "unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handleStop()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────

    private fun handleStart(intent: Intent) {
        if (captureThread?.isAlive == true) {
            Log.w(TAG, "start ignored: capture thread already running")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            failStart("missing MediaProjection consent extras")
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)

        // Promote to foreground BEFORE getting the MediaProjection token,
        // as required by Android 14+ for FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
        val notif = buildNotification(elapsedMs = 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection: MediaProjection = try {
            mpm.getMediaProjection(resultCode, resultData)
                ?: error("MediaProjectionManager returned null projection")
        } catch (e: Exception) {
            failStart("failed to obtain MediaProjection: ${e.message}")
            return
        }

        CaptureController.update {
            CaptureController.Snapshot(state = CaptureController.State.STARTING)
        }
        stopRequested = false
        captureThread = thread(name = "vezir-capture", isDaemon = true) {
            try {
                runCapture(projection, title)
            } catch (e: Throwable) {
                Log.e(TAG, "capture thread crashed", e)
                CaptureController.update {
                    it.copy(
                        state = CaptureController.State.ERROR,
                        errorMessage = e.message ?: e.javaClass.simpleName,
                    )
                }
            } finally {
                runCatching { projection.stop() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleStop() {
        stopRequested = true
        // Capture thread polls stopRequested and exits gracefully; we don't
        // forcibly interrupt or AudioRecord can leave platform state messy.
    }

    private fun failStart(reason: String) {
        Log.e(TAG, reason)
        CaptureController.update {
            it.copy(state = CaptureController.State.ERROR, errorMessage = reason)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the UI before start
    private fun runCapture(projection: MediaProjection, title: String?) {
        val outDir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outFile = File(outDir, "vezir-$stamp.ogg")

        // ── playback (system audio) capture ──
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(INPUT_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val playbackBufferBytes = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(48_000 * 2 * 2 / 5) // ~200 ms safety floor

        val playbackRecord = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(playbackBufferBytes)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        // ── mic capture ──
        val micFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(INPUT_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val micBufferBytes = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(48_000 * 2 / 5)
        val micRecord = newMicRecord(micFormat, micBufferBytes)

        val playbackResampler = PcmResampler(INPUT_SAMPLE_RATE, OUTPUT_SAMPLE_RATE, inChannels = 2)
        val micResampler = PcmResampler(INPUT_SAMPLE_RATE, OUTPUT_SAMPLE_RATE, inChannels = 1)
        val encoder = OpusEncoder(outFile, sampleRate = OUTPUT_SAMPLE_RATE, channels = 1)

        val maxMs = BuildConfig.MAX_RECORDING_MILLIS
        val readChunkFrames = INPUT_SAMPLE_RATE / 50          // 20 ms
        val playbackBuf = ShortArray(readChunkFrames * 2)
        val micBuf = ShortArray(readChunkFrames)
        // Output buffers are the resampled 16 kHz mono. 20 ms in -> ~6.66 ms out
        // factor; size with slack for ratio rounding.
        val outResamplePlayback = ShortArray(readChunkFrames + 16)
        val outResampleMic = ShortArray(readChunkFrames + 16)
        val mixBuf = ShortArray(readChunkFrames + 16)

        playbackRecord.startRecording()
        micRecord?.startRecording()

        val startElapsed = SystemClock.elapsedRealtime()
        var lastNotifMs = -1L
        var playbackSilentSinceMs: Long = -1L
        var playbackSilentLatched = false

        CaptureController.update {
            it.copy(
                state = CaptureController.State.RECORDING,
                outputFile = outFile,
                errorMessage = null,
            )
        }

        try {
            while (!stopRequested) {
                val now = SystemClock.elapsedRealtime()
                val elapsedMs = now - startElapsed
                if (elapsedMs >= maxMs) {
                    Log.i(TAG, "3h cap reached; stopping")
                    break
                }

                val pbRead = playbackRecord.read(
                    playbackBuf, 0, playbackBuf.size, AudioRecord.READ_BLOCKING,
                )
                val mcRead = micRecord?.read(
                    micBuf, 0, micBuf.size, AudioRecord.READ_BLOCKING,
                ) ?: 0

                if (pbRead < 0 || mcRead < 0) {
                    error("AudioRecord.read negative: pb=$pbRead mc=$mcRead")
                }

                val pbResampled = if (pbRead > 0) {
                    playbackResampler.resample(playbackBuf, pbRead, outResamplePlayback)
                } else 0
                val mcResampled = if (mcRead > 0) {
                    micResampler.resample(micBuf, mcRead, outResampleMic)
                } else 0
                val mixCount = minOf(pbResampled, mcResampled)

                // Silence-detection on playback: if RMS stays below -60 dBFS
                // for 10s, latch the "playback silent" hint so the UI can
                // tell the user "we appear to be recording mic only".
                val pbDb = if (pbResampled > 0) rmsDbfs(outResamplePlayback, pbResampled) else -90f
                val mcDb = if (mcResampled > 0) rmsDbfs(outResampleMic, mcResampled) else -90f
                if (pbDb < -60f) {
                    if (playbackSilentSinceMs < 0) playbackSilentSinceMs = now
                    if (!playbackSilentLatched && now - playbackSilentSinceMs >= 10_000L) {
                        playbackSilentLatched = true
                    }
                } else {
                    playbackSilentSinceMs = -1L
                    playbackSilentLatched = false
                }

                val toEncode: Int
                if (mixCount > 0 && mcResampled == pbResampled) {
                    mixAndClip(outResamplePlayback, outResampleMic, mixCount, mixBuf)
                    toEncode = mixCount
                } else if (pbResampled > 0 && mcResampled == 0) {
                    copyClipped(outResamplePlayback, pbResampled, mixBuf)
                    toEncode = pbResampled
                } else if (mcResampled > 0 && pbResampled == 0) {
                    copyClipped(outResampleMic, mcResampled, mixBuf)
                    toEncode = mcResampled
                } else if (mixCount > 0) {
                    // Lengths differ slightly due to resampler rounding; clip
                    // to mixCount and feed.
                    mixAndClip(outResamplePlayback, outResampleMic, mixCount, mixBuf)
                    toEncode = mixCount
                } else {
                    toEncode = 0
                }

                if (toEncode > 0) encoder.feed(mixBuf, toEncode)

                if (now - lastNotifMs >= 1_000L) {
                    val bytes = outFile.length()
                    val pdb = pbDb
                    val mdb = mcDb
                    val silent = playbackSilentLatched
                    CaptureController.update {
                        it.copy(
                            state = CaptureController.State.RECORDING,
                            elapsedMs = elapsedMs,
                            bytesWritten = bytes,
                            playbackRmsDbfs = pdb,
                            micRmsDbfs = mdb,
                            playbackSilent = silent,
                        )
                    }
                    notify(buildNotification(elapsedMs))
                    lastNotifMs = now
                }
            }
        } finally {
            CaptureController.update { it.copy(state = CaptureController.State.STOPPING) }
            runCatching { playbackRecord.stop() }
            runCatching { micRecord?.stop() }
            runCatching { playbackRecord.release() }
            runCatching { micRecord?.release() }
            runCatching { encoder.stopAndClose() }

            val endBytes = outFile.length()
            CaptureController.update {
                it.copy(
                    state = CaptureController.State.FINISHED,
                    bytesWritten = endBytes,
                    outputFile = outFile,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun newMicRecord(format: AudioFormat, bufferBytes: Int): AudioRecord? {
        // Try VOICE_RECOGNITION first (no AGC/AEC); fall back to UNPROCESSED
        // (cleanest but device-dependent), then MIC.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val rec = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "mic source $source builder failed: ${e.message}")
                continue
            }
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "mic source $source initialised")
                return rec
            } else {
                Log.w(TAG, "mic source $source not initialised; trying next")
                rec.release()
            }
        }
        Log.w(TAG, "no mic source available; recording playback only")
        return null
    }

    // ─────────────────────────────────────────────────────────────────

    private fun buildNotification(elapsedMs: Long): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (elapsedMs <= 0) "Starting…" else formatElapsed(elapsedMs)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Vezir is recording")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launchPi)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun notify(notif: Notification) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_ID, notif)
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
