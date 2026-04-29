package com.vezir.android.audio

import android.content.ContentResolver
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.vezir.android.capture.RecordingStorage
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Imports an existing audio source (Samsung screen-recorder MP4, voice
 * memo M4A, prior Vezir OGG, etc.) into a fresh OGG/Opus 16 kHz mono
 * file in `Music/Vezir/`. From there the regular [Uploader] takes over.
 *
 * Two code paths:
 *
 *  - **OGG passthrough**: input MIME is `audio/ogg`. We don't decode;
 *    we stream-copy the bytes into [RecordingStorage] so the imported
 *    file shows up in the user's library next to native recordings.
 *
 *  - **Transcode**: anything else. MediaExtractor selects the first
 *    audio track, MediaCodec decodes it to PCM Int16, the existing
 *    [PcmResampler] downmixes/resamples to 16 kHz mono, and the
 *    existing [OpusEncoder] writes the OGG.
 *
 * Progress reporting: best-effort. We compute progress from the input's
 * sample-time presentation timestamps relative to the format-declared
 * duration; if duration is unknown we report bytes-read fraction
 * (passthrough path) or step the progress bar incrementally.
 */
class AudioImporter(
    private val context: Context,
) {
    companion object {
        private const val TAG = "VezirAudioImporter"
        private const val TIMEOUT_US = 10_000L
        private const val INPUT_PCM_RATE_FOR_RESAMPLER = 48_000 // resampler target source rate
    }

    /** Final result of a successful import. */
    data class Result(
        val outputUri: Uri,
        val displayPath: String,
        val displayName: String,
        val bytesWritten: Long,
    )

    fun interface Progress { fun onProgress(fraction: Float) }

    /**
     * Synchronous (blocking) entry point. Run from a background dispatcher.
     *
     * @return [Result] on success
     * @throws IOException on irrecoverable read/decode/encode error
     */
    fun import(source: Uri, onProgress: Progress = Progress { }): Result {
        val resolver = context.contentResolver
        val mime = resolver.getType(source)?.lowercase(Locale.US)
        Log.i(TAG, "import start uri=$source mime=$mime")

        val displayName = "vezir-import-${stamp()}.ogg"

        return when {
            mime == "audio/ogg" || hasOggsMagic(resolver, source) -> {
                passthroughOgg(resolver, source, displayName, onProgress)
            }
            else -> {
                transcode(source, displayName, onProgress)
            }
        }
    }

    // ─────────────────────── OGG passthrough ───────────────────────

    private fun passthroughOgg(
        resolver: ContentResolver,
        source: Uri,
        displayName: String,
        onProgress: Progress,
    ): Result {
        val target = RecordingStorage.create(context, displayName)
        val totalBytes: Long = try {
            resolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) { -1L }
        try {
            resolver.openInputStream(source).use { input ->
                if (input == null) error("openInputStream null for $source")
                val buf = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    target.output.write(buf, 0, n)
                    copied += n
                    if (totalBytes > 0) {
                        onProgress.onProgress((copied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                    }
                }
                target.output.flush()
            }
            target.output.close()
            target.finalize(context)
            onProgress.onProgress(1f)
            return Result(target.uri, target.displayPath, target.displayName, target.output.bytesWritten)
        } catch (e: Throwable) {
            runCatching { target.deleteOnError(context) }
            throw e
        }
    }

    private fun hasOggsMagic(resolver: ContentResolver, source: Uri): Boolean {
        return try {
            resolver.openInputStream(source).use { input ->
                if (input == null) return false
                val header = ByteArray(4)
                val n = input.read(header)
                n >= 4 && looksLikeOggs(header, n)
            }
        } catch (_: Exception) { false }
    }

    // ─────────────────────────── Transcode ─────────────────────────

    private fun transcode(
        source: Uri,
        displayName: String,
        onProgress: Progress,
    ): Result {
        val target = RecordingStorage.create(context, displayName)
        val extractor = MediaExtractor()
        val decoder: MediaCodec
        val encoder: OpusEncoder
        val resampler: PcmResampler

        try {
            extractor.setDataSource(context, source, /* headers= */ null)
            val (audioTrackIdx, inFormat) = pickAudioTrack(extractor)
                ?: run {
                    runCatching { extractor.release() }
                    runCatching { target.output.close() }
                    runCatching { target.deleteOnError(context) }
                    throw IOException("no audio track in $source")
                }
            extractor.selectTrack(audioTrackIdx)

            val srcSampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION))
                inFormat.getLong(MediaFormat.KEY_DURATION)
            else -1L
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: "audio/unknown"
            Log.i(TAG, "transcode track=$audioTrackIdx mime=$mime sr=$srcSampleRate ch=$srcChannels durUs=$durationUs")

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            // The downstream OpusEncoder always produces 16 kHz mono. We
            // resample inside *this* class because the input rate is
            // arbitrary; PcmResampler enforces inSampleRate >= outSampleRate.
            // For inputs already at <= 16 kHz (rare; voice memos sometimes
            // are), we just don't downsample and feed the encoder whatever
            // we get after downmix (16 kHz) — handled below.

            // PcmResampler expects an integer-channel input. We always
            // downmix to mono before resampling for simplicity.
            resampler = if (srcSampleRate >= 16_000)
                PcmResampler(srcSampleRate, 16_000, inChannels = 1)
            else
                PcmResampler(16_000, 16_000, inChannels = 1) // identity-style; we won't actually call it
            encoder = OpusEncoder(target.output, sampleRate = 16_000, channels = 1)

            runDecodeLoop(extractor, decoder, resampler, encoder, srcSampleRate, srcChannels, durationUs, onProgress)

            // Flush + close encoder (writes EOS Ogg page, closes the underlying stream).
            encoder.stopAndClose()

            // Mark MediaStore entry visible.
            target.finalize(context)
            onProgress.onProgress(1f)

            return Result(target.uri, target.displayPath, target.displayName, target.output.bytesWritten)
        } catch (t: Throwable) {
            runCatching { target.deleteOnError(context) }
            throw t
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun pickAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i to fmt
        }
        return null
    }

    private fun runDecodeLoop(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        resampler: PcmResampler,
        encoder: OpusEncoder,
        srcSampleRate: Int,
        srcChannels: Int,
        durationUs: Long,
        onProgress: Progress,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastReportedFraction = -1f

        // Scratch buffers that grow on demand. We keep them as Kotlin
        // properties of the loop so we don't allocate per-frame.
        var pcmShort = ShortArray(1024)
        var monoShort = ShortArray(1024)
        var resampled = ShortArray(1024)

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx) ?: error("null decoder input buffer")
                        buf.clear()
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()

                            if (durationUs > 0L && pts >= 0L) {
                                val f = (pts.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                                if (f - lastReportedFraction >= 0.01f) {
                                    onProgress.onProgress(f)
                                    lastReportedFraction = f
                                }
                            }
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output yet; loop back.
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Some decoders deliver real format here. We pin
                        // sample rate/channels from the extractor format
                        // up-front; if the decoder reports something else,
                        // log so we can investigate.
                        val newFormat = decoder.outputFormat
                        Log.i(TAG, "decoder output format changed: $newFormat")
                    }
                    outIdx >= 0 -> {
                        val out = decoder.getOutputBuffer(outIdx)
                        if (out != null && info.size > 0) {
                            // Read PCM Int16. Some decoders deliver Int16 LE,
                            // others Int16 native; we treat the platform as
                            // little-endian which is true on all Android
                            // devices in practice.
                            val byteCount = info.size
                            val sampleCount = byteCount / 2
                            if (pcmShort.size < sampleCount) pcmShort = ShortArray(sampleCount)
                            out.position(info.offset)
                            out.limit(info.offset + byteCount)
                            out.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmShort, 0, sampleCount)

                            // Downmix to mono.
                            val frameCount = sampleCount / srcChannels
                            if (monoShort.size < frameCount) monoShort = ShortArray(frameCount)
                            downmixToMono(pcmShort, sampleCount, srcChannels, monoShort)

                            // Resample to 16 kHz mono if needed.
                            val toFeed: ShortArray
                            val toFeedCount: Int
                            if (srcSampleRate == 16_000) {
                                toFeed = monoShort
                                toFeedCount = frameCount
                            } else if (srcSampleRate > 16_000) {
                                val outCap = max(resampled.size, frameCount + 16)
                                if (resampled.size < outCap) resampled = ShortArray(outCap)
                                val n = resampler.resample(monoShort, frameCount, resampled)
                                toFeed = resampled
                                toFeedCount = n
                            } else {
                                // Upsampling not supported; this is a rare
                                // path (input < 16 kHz). Naively zero-stuff
                                // upsample to 16k by repeating samples.
                                val factor = 16_000 / srcSampleRate
                                val needed = frameCount * factor
                                if (resampled.size < needed) resampled = ShortArray(needed + 16)
                                for (i in 0 until frameCount) {
                                    val s = monoShort[i]
                                    val base = i * factor
                                    for (k in 0 until factor) resampled[base + k] = s
                                }
                                toFeed = resampled
                                toFeedCount = needed
                            }
                            if (toFeedCount > 0) encoder.feed(toFeed, toFeedCount)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}
