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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.vezir.android.BuildConfig
import com.vezir.android.MainActivity
import com.vezir.android.R
import com.vezir.android.audio.rmsDbfs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Foreground service that records the SCREEN + MIC to MP4 (v0.12.0).
 *
 * Sibling of [CaptureService] (which records mic+playback audio to OGG)
 * with the same lifecycle contract: started with the MediaProjection
 * consent extras, start/stop/pause actions, 3 h recorded-duration cap,
 * persistent notification, state published through the shared
 * [CaptureController] (with `isScreenCapture=true` so the UI routes
 * pause/stop here).
 *
 * Pipeline (no third-party dependencies):
 *
 *   MediaProjection.createVirtualDisplay()
 *     → MediaCodec H.264 surface encoder (video track)
 *   AudioRecord (mic, 48 kHz mono PCM16)
 *     → MediaCodec AAC-LC encoder (audio track)
 *   → MediaMuxer → MP4 in Movies/Vezir/ ([RecordingStorage])
 *
 * The upload flow treats the resulting .mp4 exactly like an imported
 * screen recording: the server (>= 0.18.0) extracts the audio track for
 * transcription and pulls cue frames from this file.
 *
 * Pause semantics: audio keeps being read but is discarded (the audio
 * track's sample-counter PTS stays continuous); video frames encoded
 * while paused are drained but NOT muxed, and their presentation time is
 * subtracted from the paused total so the muxed timeline has no gap.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "VezirScreenCapture"
        private const val NOTIF_ID = 0x9E92
        private const val NOTIF_CHANNEL = "vezir-screen-capture"

        const val ACTION_START = "com.vezir.android.screencapture.START"
        const val ACTION_STOP = "com.vezir.android.screencapture.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.vezir.android.screencapture.TOGGLE_PAUSE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TITLE = "title"

        private const val VIDEO_MIME = "video/avc"
        internal const val VIDEO_MAX_DIMENSION = 1920
        private const val VIDEO_BITRATE = 6_000_000
        private const val VIDEO_FPS = 30
        private const val VIDEO_IFRAME_SEC = 2
        private const val AUDIO_MIME = "audio/mp4a-latm"
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_BITRATE = 96_000
        private const val ENCODER_TIMEOUT_US = 10_000L

        fun startIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            title: String?,
        ): Intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            if (!title.isNullOrBlank()) putExtra(EXTRA_TITLE, title)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }

        fun ensureNotificationChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
                val ch = NotificationChannel(
                    NOTIF_CHANNEL,
                    "Screen recording",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Persistent notification while Vezir records the screen."
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false
    private var pipelineThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopRequested = true
            ACTION_TOGGLE_PAUSE -> handleTogglePause()
            else -> Log.w(TAG, "unknown action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────

    private fun handleStart(intent: Intent) {
        if (pipelineThread?.isAlive == true) {
            Log.w(TAG, "start ignored: pipeline already running")
            return
        }
        // Don't stomp an in-flight audio capture (shared CaptureController).
        when (CaptureController.state.value.state) {
            CaptureController.State.STARTING,
            CaptureController.State.RECORDING,
            CaptureController.State.PAUSED,
            CaptureController.State.STOPPING -> {
                Log.w(TAG, "start ignored: another capture is active")
                return
            }
            else -> {}
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            failStart("missing MediaProjection consent extras")
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE)

        // Promote to foreground BEFORE getting the MediaProjection token,
        // as required by Android 14+ (same pattern as CaptureService).
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

        // API 34+: createVirtualDisplay() throws IllegalStateException
        // ("Must register a callback before starting capture…") unless a
        // MediaProjection.Callback is registered first.  onStop fires when
        // the user revokes screen sharing from the system chip — map it to
        // a graceful stop so the MP4 finalizes instead of stalling.
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by the system; stopping capture")
                stopRequested = true
            }
        }
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        CaptureController.update {
            CaptureController.Snapshot(
                state = CaptureController.State.STARTING,
                isScreenCapture = true,
            )
        }
        stopRequested = false
        pipelineThread = thread(name = "vezir-screen-capture", isDaemon = true) {
            try {
                runPipeline(projection, title)
            } catch (e: Throwable) {
                Log.e(TAG, "screen capture pipeline crashed", e)
                CaptureController.update {
                    it.copy(
                        state = CaptureController.State.ERROR,
                        errorMessage = e.message ?: e.javaClass.simpleName,
                    )
                }
            } finally {
                runCatching { projection.unregisterCallback(projectionCallback) }
                runCatching { projection.stop() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleTogglePause() {
        pauseRequested = !pauseRequested
        val newState = if (pauseRequested)
            CaptureController.State.PAUSED else CaptureController.State.RECORDING
        CaptureController.update { it.copy(state = newState) }
        Log.i(TAG, "pause toggled: paused=$pauseRequested")
    }

    private fun failStart(reason: String) {
        Log.e(TAG, reason)
        CaptureController.update {
            it.copy(
                state = CaptureController.State.ERROR,
                isScreenCapture = true,
                errorMessage = reason,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────

    /** Everything below runs on the pipeline thread. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the UI before start
    private fun runPipeline(projection: MediaProjection, title: String?) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val displayName = "vezir-screen-$stamp.mp4"
        val target = RecordingStorage.create(this, displayName, RecordingStorage.MIME_MP4)
        try {
            runPipelineWithTarget(projection, title, target)
        } catch (t: Throwable) {
            runCatching { target.deleteOnError(this) }
            throw t
        }
    }

    private fun runPipelineWithTarget(
        projection: MediaProjection,
        title: String?,
        target: RecordingStorage.RecordingTarget,
    ) {
        // The muxer writes at the FD level; the storage OutputStream goes
        // unused (created as part of the MediaStore/fallback handling).
        runCatching { target.output.close() }
        val pfd: ParcelFileDescriptor = target.openWriteFd(this)
        val muxer = MediaMuxer(
            pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )

        // ── video encoder (H.264, surface input) ──
        val metrics = resources.displayMetrics
        val (width, height) = scaledVideoSize(metrics.widthPixels, metrics.heightPixels)
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_SEC)
        }
        val videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME)
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = videoEncoder.createInputSurface()
        videoEncoder.start()
        val virtualDisplay: VirtualDisplay = projection.createVirtualDisplay(
            "vezir-screen", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null,
        )

        // ── audio encoder (AAC-LC) + mic ──
        val audioFormat = MediaFormat.createAudioFormat(
            AUDIO_MIME, AUDIO_SAMPLE_RATE, 1,
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        }
        val audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME)
        audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncoder.start()

        val micFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val micBufferBytes = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(AUDIO_SAMPLE_RATE * 2 / 5) // ~200 ms floor
        val micRecord = newMicRecord(micFormat, micBufferBytes)

        // ── muxer coordination (both tracks must be added before start) ──
        val muxerState = MuxerState(muxer)

        // Pause accounting for the video track: encoded PTS (micros, from
        // the surface clock) minus the total time spent paused.  Audio PTS
        // comes from a sample counter, so it is continuous by construction.
        val pausedTotalUs = java.util.concurrent.atomic.AtomicLong(0L)
        val pauseStartUs = java.util.concurrent.atomic.AtomicLong(0L)
        fun currentPausedTotalUs(): Long {
            val start = pauseStartUs.get()
            return pausedTotalUs.get() +
                if (start > 0) System.nanoTime() / 1_000 - start else 0L
        }

        val startElapsed = SystemClock.elapsedRealtime()
        var lastNotifMs = -1L
        var lastLevelMs = -1L
        var micSamplesFed = 0L

        CaptureController.update {
            it.copy(
                state = CaptureController.State.RECORDING,
                isScreenCapture = true,
                outputUri = target.uri,
                displayName = target.displayName,
                displayPath = target.displayPath,
                errorMessage = null,
            )
        }

        // ── video drain thread ──
        val videoInfo = MediaCodec.BufferInfo()
        val videoPtsBaseUs = java.util.concurrent.atomic.AtomicLong(-1L)
        val videoDrain = thread(name = "vezir-screen-video", isDaemon = true) {
            var eos = false
            while (!eos) {
                val outIdx = videoEncoder.dequeueOutputBuffer(videoInfo, ENCODER_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopRequested) videoEncoder.signalEndOfInputStream()
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxerState.addVideoTrack(videoEncoder.outputFormat)
                    }
                    outIdx >= 0 -> {
                        val buf = videoEncoder.getOutputBuffer(outIdx)
                        val isConfig =
                            (videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isEos =
                            (videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (buf != null && videoInfo.size > 0 && !isConfig) {
                            if (!pauseRequested) {
                                // v0.12.2: rebase the video timeline to the
                                // first frame.  Surface timestamps are
                                // boot-clock based (e.g. 270487 s), which
                                // made absolute ffmpeg -ss seeks land before
                                // the first frame — the server could not
                                // extract cue frames from 0.12.0/0.12.1
                                // recordings.  Audio PTS (sample counter)
                                // already starts at 0, so A/V stays aligned.
                                if (videoPtsBaseUs.get() < 0) {
                                    videoPtsBaseUs.set(videoInfo.presentationTimeUs)
                                }
                                videoInfo.presentationTimeUs = rebasedVideoPts(
                                    videoInfo.presentationTimeUs,
                                    videoPtsBaseUs.get(),
                                    currentPausedTotalUs(),
                                )
                                muxerState.writeVideo(buf, videoInfo)
                            }
                            // Paused: drain but drop — the resumed timeline
                            // continues at (raw PTS - base - paused total).
                        }
                        videoEncoder.releaseOutputBuffer(outIdx, false)
                        if (isEos) eos = true
                    }
                }
            }
        }

        // ── mic capture + audio encode loop (this thread) ──
        val audioInfo = MediaCodec.BufferInfo()
        val readChunkFrames = AUDIO_SAMPLE_RATE / 50 // 20 ms
        val micBuf = ShortArray(readChunkFrames)
        val pcmBytes = ByteArray(readChunkFrames * 2)
        micRecord?.startRecording()

        try {
            var audioInputDone = micRecord == null
            if (micRecord == null) {
                Log.w(TAG, "no mic source; recording screen video only")
                // Queue EOS immediately so the audio encoder still reports
                // its output format (and the muxer can start with both
                // tracks added); the track simply contains no samples.
                queueAudioEos(audioEncoder)
            }
            while (!stopRequested || !audioInputDone) {
                val now = SystemClock.elapsedRealtime()
                val elapsedMs = now - startElapsed

                // Pause transitions.
                if (pauseRequested && pauseStartUs.get() == 0L) {
                    pauseStartUs.set(System.nanoTime() / 1_000)
                } else if (!pauseRequested && pauseStartUs.get() > 0L) {
                    pausedTotalUs.addAndGet(System.nanoTime() / 1_000 - pauseStartUs.get())
                    pauseStartUs.set(0L)
                }

                // 3h cap on RECORDED audio (mic sample counter excludes
                // paused time by construction).  A mic-less recording uses
                // elapsed-minus-paused instead.
                val recordedMs = if (micRecord != null)
                    micSamplesFed * 1_000L / AUDIO_SAMPLE_RATE
                else
                    elapsedMs - currentPausedTotalUs() / 1_000L
                if (recordedMs >= BuildConfig.MAX_RECORDING_MILLIS) {
                    Log.i(TAG, "3h recorded cap reached; stopping")
                    stopRequested = true
                }

                if (!audioInputDone) {
                    val n = micRecord!!.read(
                        micBuf, 0, micBuf.size, AudioRecord.READ_BLOCKING,
                    )
                    if (n < 0) error("AudioRecord.read negative: $n")

                    // Mic level for the spectrometer (~10 Hz).
                    if (now - lastLevelMs >= 100L) {
                        lastLevelMs = now
                        val db = if (n > 0 && !pauseRequested)
                            rmsDbfs(micBuf, n) else -90f
                        CaptureController.updateLevels(-90f, db)
                    }

                    if (pauseRequested) {
                        // Discard while paused — sample counter (and thus
                        // audio PTS) does not advance.
                    } else if (stopRequested) {
                        // Stop requested: queue EOS after what we already fed.
                        queueAudioEos(audioEncoder)
                        audioInputDone = true
                    } else {
                        // PCM16 LE bytes → encoder input with sample-counter PTS.
                        var j = 0
                        for (i in 0 until n) {
                            val s = micBuf[i].toInt()
                            pcmBytes[j++] = (s and 0xFF).toByte()
                            pcmBytes[j++] = ((s shr 8) and 0xFF).toByte()
                        }
                        val inIdx = audioEncoder.dequeueInputBuffer(ENCODER_TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = audioEncoder.getInputBuffer(inIdx)
                                ?: error("null audio encoder input buffer")
                            inBuf.clear()
                            inBuf.put(pcmBytes, 0, n * 2)
                            val ptsUs = micSamplesFed * 1_000_000L / AUDIO_SAMPLE_RATE
                            audioEncoder.queueInputBuffer(inIdx, 0, n * 2, ptsUs, 0)
                            micSamplesFed += n
                        }
                    }
                } else {
                    // Mic-less recording: the blocking read normally paces
                    // this loop at ~20 ms; keep it from spinning hot.
                    SystemClock.sleep(20)
                }

                // Drain audio encoder output.
                while (true) {
                    val outIdx = audioEncoder.dequeueOutputBuffer(audioInfo, 0)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            muxerState.addAudioTrack(audioEncoder.outputFormat)
                        outIdx >= 0 -> {
                            val buf = audioEncoder.getOutputBuffer(outIdx)
                            val isConfig =
                                (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (buf != null && audioInfo.size > 0 && !isConfig) {
                                muxerState.writeAudio(buf, audioInfo)
                            }
                            audioEncoder.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }

                // 1 Hz status + notification.
                if (now - lastNotifMs >= 1_000L) {
                    lastNotifMs = now
                    val recMs = recordedMs.coerceAtLeast(0)
                    val bytes: Long = runCatching { pfd.statSize }.getOrDefault(0L)
                    CaptureController.update {
                        it.copy(
                            state = if (pauseRequested)
                                CaptureController.State.PAUSED
                            else CaptureController.State.RECORDING,
                            elapsedMs = elapsedMs,
                            recordingMs = recMs,
                            bytesWritten = bytes,
                        )
                    }
                    notify(buildNotification(recMs, paused = pauseRequested))
                }
            }

            // Stop requested while paused never queued the audio EOS —
            // make sure the AAC track terminates cleanly either way.
            if (!audioInputDone) {
                runCatching { queueAudioEos(audioEncoder) }
            }

            // Final drain: flush audio encoder output through EOS so the
            // track is complete before the muxer stops (bounded guard).
            var sawAudioEos = false
            var guard = 0
            while (!sawAudioEos && guard++ < 200) {
                when (val outIdx = audioEncoder.dequeueOutputBuffer(audioInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* keep draining */ }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        muxerState.addAudioTrack(audioEncoder.outputFormat)
                    else -> if (outIdx >= 0) {
                        val buf = audioEncoder.getOutputBuffer(outIdx)
                        val isConfig =
                            (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (buf != null && audioInfo.size > 0 && !isConfig) {
                            muxerState.writeAudio(buf, audioInfo)
                        }
                        if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawAudioEos = true
                        }
                        audioEncoder.releaseOutputBuffer(outIdx, false)
                    }
                }
            }

            // Signal the video surface encoder and let the drain thread
            // flush the tail before we tear down.
            runCatching { videoEncoder.signalEndOfInputStream() }
            videoDrain.join(10_000)
        } finally {
            CaptureController.update { it.copy(state = CaptureController.State.STOPPING) }
            runCatching { micRecord?.stop() }
            runCatching { micRecord?.release() }
            runCatching { virtualDisplay.release() }
            runCatching { videoEncoder.stop() }
            runCatching { videoEncoder.release() }
            runCatching { audioEncoder.stop() }
            runCatching { audioEncoder.release() }
            muxerState.release()
            runCatching { pfd.close() }
            // Mark the MediaStore entry visible (no-op on the fallback path).
            target.finalize(this)

            val endBytes: Long = runCatching {
                contentResolver.openFileDescriptor(target.uri, "r")
                    ?.use { fd -> fd.statSize } ?: 0L
            }.getOrDefault(0L)
            CaptureController.update {
                it.copy(
                    state = CaptureController.State.FINISHED,
                    isScreenCapture = true,
                    bytesWritten = endBytes,
                    outputUri = target.uri,
                    displayName = target.displayName,
                    displayPath = target.displayPath,
                )
            }
        }
    }

    private fun queueAudioEos(audioEncoder: MediaCodec) {
        val inIdx = audioEncoder.dequeueInputBuffer(ENCODER_TIMEOUT_US)
        if (inIdx >= 0) {
            audioEncoder.queueInputBuffer(
                inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun newMicRecord(format: AudioFormat, bufferBytes: Int): AudioRecord? {
        // Same source preference as CaptureService: VOICE_RECOGNITION first
        // (no AGC/AEC), then UNPROCESSED, then MIC.
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
            }
            Log.w(TAG, "mic source $source not initialised; trying next")
            rec.release()
        }
        Log.w(TAG, "no mic source available")
        return null
    }

    // ─────────────────────────────────────────────────────────────────

    /**
     * Muxer lifecycle: tracks are added when each encoder reports its
     * output format; the muxer starts once BOTH tracks exist; all writes
     * are serialized here so the two encoder threads can't interleave a
     * start/write/stop.
     */
    private class MuxerState(private val muxer: MediaMuxer) {
        private val lock = Object()
        private var videoTrack = -1
        private var audioTrack = -1
        private var started = false
        private var released = false

        fun addVideoTrack(format: MediaFormat) = synchronized(lock) {
            if (videoTrack < 0) {
                videoTrack = muxer.addTrack(format)
                maybeStart()
            }
        }

        fun addAudioTrack(format: MediaFormat) = synchronized(lock) {
            if (audioTrack < 0) {
                audioTrack = muxer.addTrack(format)
                maybeStart()
            }
        }

        fun writeVideo(buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) =
            synchronized(lock) {
                if (started && !released) muxer.writeSampleData(videoTrack, buf, info)
            }

        fun writeAudio(buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) =
            synchronized(lock) {
                if (started && !released) muxer.writeSampleData(audioTrack, buf, info)
            }

        fun release() = synchronized(lock) {
            if (!released) {
                released = true
                if (started) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
        }

        private fun maybeStart() {
            if (!started && videoTrack >= 0 && audioTrack >= 0) {
                muxer.start()
                started = true
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────

    private fun buildNotification(elapsedMs: Long, paused: Boolean = false): Notification {
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
        val pausePi = PendingIntent.getService(
            this, 2,
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (paused) "Vezir — screen recording paused"
            else "Vezir is recording the screen"
        val text = if (elapsedMs <= 0) "Starting…"
            else if (paused) "${formatElapsed(elapsedMs)} (paused)"
            else formatElapsed(elapsedMs)
        val pauseLabel = if (paused) "Resume" else "Pause"
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launchPi)
            .addAction(0, pauseLabel, pausePi)
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

/**
 * Scale the display size to fit [ScreenCaptureService.VIDEO_MAX_DIMENSION]
 * on the longest side, preserving aspect, aligned to 16 px (H.264
 * macroblock friendliness). Never upscales.
 */
internal fun scaledVideoSize(srcW: Int, srcH: Int): Pair<Int, Int> {
    val longest = maxOf(srcW, srcH).coerceAtLeast(1)
    val scale = minOf(1f, ScreenCaptureService.VIDEO_MAX_DIMENSION.toFloat() / longest)
    fun align16(v: Int) = (v / 16).coerceAtLeast(1) * 16
    return align16((srcW * scale).toInt()) to align16((srcH * scale).toInt())
}

/**
 * Rebase a video frame's presentation time so the first recorded frame
 * lands at ~0 (v0.12.2).  [rawPtsUs] is the boot-clock surface timestamp,
 * [basePtsUs] the first frame's raw PTS, [pausedTotalUs] the cumulative
 * time spent paused.  Clamped at 0 so a stray early frame can never go
 * negative (MediaMuxer rejects non-monotonic/negative PTS).
 */
internal fun rebasedVideoPts(rawPtsUs: Long, basePtsUs: Long, pausedTotalUs: Long): Long =
    (rawPtsUs - basePtsUs - pausedTotalUs).coerceAtLeast(0L)
