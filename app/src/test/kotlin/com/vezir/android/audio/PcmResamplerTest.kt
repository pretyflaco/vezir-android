package com.vezir.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PcmResamplerTest {

    /**
     * 1 s of stereo silence at 48 kHz should produce 1 s of mono silence
     * at 16 kHz, with sample count within ±2 of expected (resampler
     * fractional-position rounding).
     */
    @Test
    fun silenceProducesSilence() {
        val r = PcmResampler(48_000, 16_000, inChannels = 2)
        val inFrames = 48_000
        val input = ShortArray(inFrames * 2)
        val out = ShortArray(inFrames + 16)
        val n = r.resample(input, input.size, out)
        assertTrue("expected ~16000 out, got $n", abs(n - 16_000) <= 2)
        for (i in 0 until n) assertEquals(0, out[i].toInt())
    }

    /**
     * Resampling a steady DC offset must preserve the offset (within a
     * couple of LSBs from clipping/rounding).
     */
    @Test
    fun dcOffsetPreserved() {
        val r = PcmResampler(48_000, 16_000, inChannels = 2)
        val inFrames = 48_000
        val input = ShortArray(inFrames * 2) { 1000 }
        val out = ShortArray(inFrames + 16)
        val n = r.resample(input, input.size, out)
        assertTrue(n > 15_000)
        for (i in 1 until n) {
            assertTrue(
                "DC drift at $i: got=${out[i]}",
                abs(out[i].toInt() - 1000) <= 2,
            )
        }
    }

    /**
     * Resampling a 400 Hz sine across two chunks should be seamless: the
     * concatenated output should not contain a discontinuity at the
     * chunk boundary larger than the per-sample deltas around it.
     */
    @Test
    fun chunkBoundaryIsSeamless() {
        val r = PcmResampler(48_000, 16_000, inChannels = 1)
        val freq = 400.0
        val sr = 48_000.0
        val chunkFrames = 9_600 // 200 ms; not a multiple of 48000/16000=3
        val chunk1 = ShortArray(chunkFrames) { (sin(2 * PI * freq * it / sr) * 20_000).toInt().toShort() }
        val chunk2 = ShortArray(chunkFrames) {
            (sin(2 * PI * freq * (it + chunkFrames) / sr) * 20_000).toInt().toShort()
        }
        val out1 = ShortArray(chunkFrames + 16)
        val out2 = ShortArray(chunkFrames + 16)
        val n1 = r.resample(chunk1, chunk1.size, out1)
        val n2 = r.resample(chunk2, chunk2.size, out2)
        // Compute neighborhood max delta around the join.
        val joinDelta = abs(out2[0].toInt() - out1[n1 - 1].toInt())
        // Neighbouring deltas elsewhere in the signal:
        val neighborhoodMax = (1 until n1).maxOf { abs(out1[it].toInt() - out1[it - 1].toInt()) }
        assertTrue(
            "join discontinuity $joinDelta should be in line with neighborhood max $neighborhoodMax",
            joinDelta <= neighborhoodMax + 200,
        )
    }
}

class MixAndClipTest {

    @Test
    fun summingTwoSilentStreamsYieldsSilence() {
        val a = ShortArray(64)
        val b = ShortArray(64)
        val out = ShortArray(64)
        mixAndClip(a, b, 64, out)
        for (s in out) assertEquals(0, s.toInt())
    }

    @Test
    fun softClipKeepsValuesWithinInt16Range() {
        val a = ShortArray(64) { 32_000 }
        val b = ShortArray(64) { 32_000 }
        val out = ShortArray(64)
        mixAndClip(a, b, 64, out)
        for (s in out) {
            assertTrue("clip exceeded short range: $s", abs(s.toInt()) <= 32_767)
        }
    }
}

class RmsDbfsTest {

    @Test
    fun silenceFloorIsMinus90() {
        val s = ShortArray(320)
        assertEquals(-90f, rmsDbfs(s, 320), 0.01f)
    }

    @Test
    fun fullScaleIsAroundZero() {
        val s = ShortArray(320) { if (it % 2 == 0) 32_767 else (-32_767).toShort() }
        // Square wave at full deflection -> RMS = 32767 -> 0 dBFS.
        assertTrue(rmsDbfs(s, 320) >= -0.5f)
    }
}

class DownmixToMonoTest {

    @Test
    fun monoIsAMemcpy() {
        val src = shortArrayOf(1, 2, 3, 4, 5)
        val out = ShortArray(5)
        downmixToMono(src, 5, channels = 1, out = out)
        for (i in src.indices) assertEquals(src[i], out[i])
    }

    @Test
    fun stereoAveragesChannels() {
        // L=1000 R=2000 -> avg=1500 per frame
        val src = ShortArray(8) { if (it % 2 == 0) 1000 else 2000 }
        val out = ShortArray(4)
        downmixToMono(src, 8, channels = 2, out = out)
        for (i in 0 until 4) assertEquals(1500, out[i].toInt())
    }

    @Test
    fun fiveOneAveragesAllChannels() {
        val channels = 6
        val frames = 3
        val src = ShortArray(frames * channels) { (it % channels * 100).toShort() } // 0,100,200,300,400,500
        val out = ShortArray(frames)
        downmixToMono(src, src.size, channels, out)
        // Per-frame sum 0+100+...+500 = 1500; /6 = 250
        for (i in 0 until frames) assertEquals(250, out[i].toInt())
    }
}

class LooksLikeOggsTest {

    @Test
    fun acceptsOggsHeader() {
        val data = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte(), 0x00)
        assertTrue(looksLikeOggs(data))
    }

    @Test
    fun rejectsTooShort() {
        assertTrue(!looksLikeOggs(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte())))
    }

    @Test
    fun rejectsWrongMagic() {
        val data = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())
        assertTrue(!looksLikeOggs(data))
    }

    @Test
    fun usesExplicitLengthBound() {
        val data = ByteArray(8)
        data[0] = 'O'.code.toByte(); data[1] = 'g'.code.toByte()
        data[2] = 'g'.code.toByte(); data[3] = 'S'.code.toByte()
        assertTrue(!looksLikeOggs(data, length = 3))
        assertTrue(looksLikeOggs(data, length = 4))
    }
}
