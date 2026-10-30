package com.vezir.android.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceWindowTest {

    @Test
    fun `silence is reported only after the full window`() {
        val window = SilenceWindow(durationMs = 10_000L)

        window.update(-90f, 1_000L)
        window.update(-90f, 10_999L)
        assertFalse(window.silent)

        window.update(-90f, 11_000L)
        assertTrue(window.silent)
    }

    @Test
    fun `audible input resets the window and clears the warning`() {
        val window = SilenceWindow(durationMs = 10_000L)
        window.update(-90f, 0L)
        window.update(-90f, 10_000L)
        assertTrue(window.silent)

        window.update(-20f, 10_100L)
        assertFalse(window.silent)

        window.update(-90f, 10_200L)
        window.update(-90f, 19_999L)
        assertFalse(window.silent)
        window.update(-90f, 20_200L)
        assertTrue(window.silent)
    }

    @Test
    fun `threshold boundary is treated as audible`() {
        val window = SilenceWindow(durationMs = 100L)
        window.update(-60f, 0L)
        window.update(-60f, 100L)
        assertFalse(window.silent)
    }
}
