package com.vezir.android.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Wraps [MediaCodec] running the platform Opus encoder.
 *
 * Pipeline contract:
 *   1. caller resamples input to 16 kHz mono Int16
 *   2. caller calls [feed] with PCM blocks (any size); we buffer to
 *      20 ms (= 320 samples) frames
 *   3. encoder spits out Opus packets which we forward to [OggOpusWriter]
 *
 * 20 ms frames are the standard speech setting, the same setting the
 * Android platform encoder is tuned for. 16 kHz mono at 24 kbps is the
 * v1 plan target.
 *
 * Granule position bookkeeping: Ogg/Opus's reference rate is 48 kHz
 * regardless of the input sample rate, so we advance granule by
 * `frameSamples * (48000/inputRate)` after each frame.
 */
class OpusEncoder(
    output: File,
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
    bitrate: Int = 24_000,
) {
    companion object {
        private const val TAG = "VezirOpusEncoder"
        const val FRAME_MS = 20
        const val OPUS_REFERENCE_RATE = 48_000
    }

    private val frameSamples: Int = sampleRate * FRAME_MS / 1000   // 320 @16k
    private val frameBytes: Int = frameSamples * channels * 2      // Int16 PCM
    private val frameBuffer: ByteArray = ByteArray(frameBytes)
    private var frameFill: Int = 0
    private var presentationUs: Long = 0L
    private var granule48k: Long = 0L
    private val perFrameGranule: Long = (frameSamples.toLong() * OPUS_REFERENCE_RATE) / sampleRate

    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private val ogg = OggOpusWriter(output, serial = Random.nextInt())
    private var idHeaderEmitted = false
    private var tagsHeaderEmitted = false
    private var stopped = false

    init {
        require(channels == 1) { "v1 emits mono only" }
        require(sampleRate == 16_000) { "v1 emits 16 kHz only" }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            // CSD-0 is filled by the encoder. Some devices need an explicit
            // max-input-size to avoid "buffer too small" exceptions on
            // larger feeds; we keep our feeds at exactly one frame so
            // KEY_MAX_INPUT_SIZE is just frameBytes.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
            // Some encoders honour KEY_AAC_PROFILE-style hints; not needed for Opus.
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        // Emit headers immediately so the file is a valid Ogg/Opus from
        // byte 0 even if no audio frames arrive (defensive).
        ogg.writeIdHeader()
        idHeaderEmitted = true
        ogg.writeTagsHeader(vendor = "vezir-android")
        tagsHeaderEmitted = true
    }

    /**
     * Feed PCM 16-bit mono LE samples. May be any length; internally
     * buffered to [FRAME_MS] frames.
     */
    fun feed(pcm: ShortArray, count: Int) {
        if (stopped) return
        var read = 0
        while (read < count) {
            val space = (frameBytes - frameFill) / 2
            val take = minOf(space, count - read)
            // Copy `take` Int16 samples from pcm[read..read+take] to frameBuffer[frameFill..]
            for (i in 0 until take) {
                val s = pcm[read + i].toInt()
                frameBuffer[frameFill + i * 2]     = (s and 0xFF).toByte()
                frameBuffer[frameFill + i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
            }
            frameFill += take * 2
            read += take
            if (frameFill == frameBytes) {
                submitFrame(frameBuffer, frameBytes, endOfStream = false)
                frameFill = 0
            }
        }
    }

    /**
     * Stop, drain, and close. Pads any partial last frame with silence so
     * we don't lose its lead-in.
     */
    fun stopAndClose() {
        if (stopped) return
        stopped = true

        if (frameFill > 0) {
            // Zero-pad to a full frame.
            for (i in frameFill until frameBytes) frameBuffer[i] = 0
            submitFrame(frameBuffer, frameBytes, endOfStream = true)
            frameFill = 0
        } else {
            submitFrame(frameBuffer, 0, endOfStream = true)
        }

        // Drain remaining output until EOS.
        drain(untilEos = true)

        try {
            codec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec.stop() raised", e)
        }
        codec.release()
        ogg.close()
    }

    // ──────────────────────────── Internals ──────────────────────────

    private fun submitFrame(data: ByteArray, length: Int, endOfStream: Boolean) {
        // Always drain whatever's already queued before queueing new input,
        // otherwise dequeueInputBuffer can stall on slow devices.
        drain(untilEos = false)

        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        var attempts = 0
        while (true) {
            val inIdx = codec.dequeueInputBuffer(20_000) // 20 ms
            if (inIdx >= 0) {
                val buf = codec.getInputBuffer(inIdx) ?: error("null input buffer at $inIdx")
                buf.clear()
                if (length > 0) buf.put(data, 0, length)
                codec.queueInputBuffer(inIdx, 0, length, presentationUs, flags)
                presentationUs += FRAME_MS * 1000L
                return
            }
            attempts++
            if (attempts > 50) error("encoder input stuck for >1s")
            drain(untilEos = false)
        }
    }

    private fun drain(untilEos: Boolean) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    Thread.sleep(1)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Codec-specific data (CSD-0 = OpusHead) can arrive via
                    // format change on some devices, but RFC 7845 specifies
                    // a fixed OpusHead — we already emitted ours. Ignore.
                }
                outIdx >= 0 -> {
                    val out = codec.getOutputBuffer(outIdx)
                    if (out != null && bufferInfo.size > 0) {
                        // BUFFER_FLAG_CODEC_CONFIG packets carry CSD; skip.
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val packet = ByteArray(bufferInfo.size)
                            out.position(bufferInfo.offset)
                            out.limit(bufferInfo.offset + bufferInfo.size)
                            out.get(packet)
                            granule48k += perFrameGranule
                            ogg.writeAudioPacket(packet, granule48k)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        ogg.finishWithEos()
                        return
                    }
                }
                else -> return
            }
        }
    }
}
