package com.vezir.android.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * Resampling, downmix, and mix utilities for the v1 capture pipeline.
 *
 * Capture is 48 kHz stereo Int16. Output is 16 kHz mono Int16. We do:
 *
 *   1. interleaved-stereo Int16  ->  per-frame mono Float (downmix)
 *   2. linear-interpolation downsample 48 kHz -> 16 kHz
 *   3. (in [mixAndClip]) two mono streams summed with soft-clip via tanh
 *
 * Linear interpolation is good enough for speech ASR. Whisper feeds 16 kHz
 * mono, so any artifacts from a fancier resampler would not survive its
 * own log-mel front-end. Avoiding a heavyweight DSP dep keeps the APK
 * small and the build hermetic.
 *
 * State [PcmResampler] is per-stream (one for playback, one for mic). A
 * fractional read position is kept across [resample] calls so chunked
 * input produces seamless output.
 */
class PcmResampler(
    private val inSampleRate: Int = 48_000,
    private val outSampleRate: Int = 16_000,
    private val inChannels: Int = 2,
) {
    init {
        require(inSampleRate > 0 && outSampleRate > 0) { "sample rates must be > 0" }
        require(inChannels in 1..2) { "only mono or stereo input supported" }
        require(inSampleRate >= outSampleRate) {
            "downsampling only; got in=$inSampleRate out=$outSampleRate"
        }
    }

    private val ratio: Double = inSampleRate.toDouble() / outSampleRate.toDouble()

    /**
     * Last input mono sample carried over from the previous chunk, so
     * interpolation across the chunk boundary is seamless.
     */
    private var lastInSample: Float = 0f

    /**
     * Fractional input position (samples). Integer part lives within the
     * current chunk; the fractional part determines interpolation weight.
     */
    private var phase: Double = 0.0

    /**
     * Resample [interleavedIn] (Int16, [inChannels]-interleaved) to
     * Int16 mono [out] at [outSampleRate]. Returns the number of output
     * samples written. The caller must ensure [out] is at least
     * `ceil(inFrames / ratio) + 1` long.
     *
     * @param inSampleCount total Int16 samples in [interleavedIn]
     *                      (= frames * inChannels). If zero, returns 0.
     */
    fun resample(interleavedIn: ShortArray, inSampleCount: Int, out: ShortArray): Int {
        if (inSampleCount <= 0) return 0
        val inFrames = inSampleCount / inChannels
        if (inFrames <= 0) return 0

        // Conceptual virtual-frame stream visible to interpolation:
        //   virtual[0]      = lastInSample (carried over from previous chunk)
        //   virtual[1..N]   = monoSampleAt(in, k-1) for k in 1..inFrames
        // `phase` is the next output's position in this virtual stream.
        // Each iteration interpolates virtual[i0] and virtual[i0+1].

        var written = 0
        while (true) {
            val srcIdx = phase
            val i0 = srcIdx.toInt()
            val i1 = i0 + 1
            if (i1 > inFrames) break // would read past the end of this chunk
            val frac = (srcIdx - i0).toFloat()

            val s0: Float = if (i0 == 0) lastInSample else monoSampleAt(interleavedIn, i0 - 1)
            val s1: Float = monoSampleAt(interleavedIn, i1 - 1)
            val sample = s0 + (s1 - s0) * frac

            out[written++] = clipToShort(sample)
            phase += ratio
        }

        // Slide phase into next chunk's coordinate system.
        // We've consumed virtual frames [0..inFrames]; subtract inFrames so
        // the next chunk's virtual[0] = (this chunk's last real frame).
        phase -= inFrames
        lastInSample = monoSampleAt(interleavedIn, inFrames - 1)
        return written
    }

    private fun monoSampleAt(interleaved: ShortArray, frame: Int): Float {
        return if (inChannels == 1) {
            interleaved[frame].toFloat()
        } else {
            val l = interleaved[frame * 2].toInt()
            val r = interleaved[frame * 2 + 1].toInt()
            ((l + r) * 0.5f)
        }
    }

    /** Reset state; useful when restarting a stream. */
    fun reset() {
        lastInSample = 0f
        phase = 0.0
    }
}

/**
 * Sum two mono Int16 streams of the same length with soft-clip via
 * `tanh` so simultaneous loud playback + mic don't wrap. Writes into
 * [out]; [out] must be at least [count] samples long.
 *
 * The tanh shape keeps the result in [-32767, 32767] without harsh
 * clipping. 0.9 / 32767f scales the input to the linear region of tanh
 * before the saturation knee.
 */
fun mixAndClip(
    a: ShortArray,
    b: ShortArray,
    count: Int,
    out: ShortArray,
    aGain: Float = 1.0f,
    bGain: Float = 1.0f,
) {
    require(count <= a.size && count <= b.size && count <= out.size) {
        "buffers too small for count=$count"
    }
    // Normalize the soft-clip knee around full-scale Int16. The input is
    // already roughly in [-32768, 32767]; scale so a single channel at
    // full deflection lands near tanh(0.9) ~ 0.716 (still linear-ish),
    // and a two-channel sum at full deflection saturates gracefully.
    val knee = 0.9f / 32767f
    for (i in 0 until count) {
        val sum = a[i].toInt() * aGain + b[i].toInt() * bGain
        val saturated = tanh(sum * knee)
        out[i] = (saturated * 32767f).toInt().toShort()
    }
}

/** Sum a single mono stream into [out] (used in mic-only fallback). */
fun copyClipped(src: ShortArray, count: Int, out: ShortArray, gain: Float = 1.0f) {
    require(count <= src.size && count <= out.size)
    if (gain == 1.0f) {
        System.arraycopy(src, 0, out, 0, count)
        return
    }
    for (i in 0 until count) {
        out[i] = clipToShort(src[i].toInt() * gain)
    }
}

/**
 * Downmix interleaved PCM Int16 to mono by averaging channels per frame.
 * Stable across channels=1 (memcpy) and channels>=2.
 */
fun downmixToMono(src: ShortArray, sampleCount: Int, channels: Int, out: ShortArray) {
    require(channels >= 1) { "channels must be >= 1" }
    require(out.size >= sampleCount / channels) { "out too small for downmix" }
    if (channels == 1) {
        System.arraycopy(src, 0, out, 0, sampleCount)
        return
    }
    val frames = sampleCount / channels
    for (f in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) sum += src[f * channels + c].toInt()
        out[f] = (sum / channels).toShort()
    }
}

/** True if the first 4 bytes of [data] are the Ogg "OggS" magic. */
fun looksLikeOggs(data: ByteArray, length: Int = data.size): Boolean {
    if (length < 4) return false
    return data[0] == 'O'.code.toByte() && data[1] == 'g'.code.toByte() &&
        data[2] == 'g'.code.toByte() && data[3] == 'S'.code.toByte()
}

/** RMS of an Int16 mono buffer in dBFS, clamped to [-90, 0]. */
fun rmsDbfs(samples: ShortArray, count: Int): Float {
    if (count <= 0) return -90f
    var sumSq = 0.0
    for (i in 0 until count) {
        val s = samples[i].toInt()
        sumSq += (s * s).toDouble()
    }
    val rms = kotlin.math.sqrt(sumSq / count)
    if (rms < 1.0) return -90f
    val dbfs = 20.0 * kotlin.math.log10(rms / 32767.0)
    return max(-90.0, min(0.0, dbfs)).toFloat()
}

private fun clipToShort(v: Float): Short {
    val clamped = if (abs(v) > 32767f) (if (v >= 0f) 32767f else -32767f) else v
    return clamped.toInt().toShort()
}
