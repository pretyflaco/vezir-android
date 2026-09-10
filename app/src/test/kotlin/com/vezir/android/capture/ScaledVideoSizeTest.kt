package com.vezir.android.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaledVideoSizeTest {

    @Test
    fun `landscape 4k caps at 1920 on the long side`() {
        // 3840x2160 → scale 0.5 → 1920x1080 → 16-aligned: 1920x1072.
        assertEquals(1920 to 1072, scaledVideoSize(3840, 2160))
    }

    @Test
    fun `portrait phone display caps at 1920 on the long side`() {
        // 1080x2400 → scale 0.8 → 864x1920 (16-aligned).
        assertEquals(864 to 1920, scaledVideoSize(1080, 2400))
    }

    @Test
    fun `small display is not upscaled but is 16-aligned`() {
        assertEquals(800 to 592, scaledVideoSize(800, 600))
    }

    @Test
    fun `already aligned size is unchanged`() {
        assertEquals(1920 to 1088, scaledVideoSize(1920, 1088))
    }

    @Test
    fun `degenerate display yields a minimum 16px video`() {
        assertEquals(16 to 16, scaledVideoSize(0, 0))
    }
}
