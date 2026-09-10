package com.vezir.android.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class RebasedVideoPtsTest {

    @Test
    fun `first frame rebases to zero`() {
        assertEquals(0L, rebasedVideoPts(270_487_521_800L, 270_487_521_800L, 0L))
    }

    @Test
    fun `later frame keeps relative offset`() {
        // 65 s after the first frame, no pauses.
        assertEquals(
            65_000_000L,
            rebasedVideoPts(270_487_521_800L + 65_000_000L, 270_487_521_800L, 0L),
        )
    }

    @Test
    fun `paused time is subtracted`() {
        // 65 s after first frame with a 20 s pause → 45 s on the timeline.
        assertEquals(
            45_000_000L,
            rebasedVideoPts(
                270_487_521_800L + 65_000_000L, 270_487_521_800L, 20_000_000L,
            ),
        )
    }

    @Test
    fun `result never goes negative`() {
        assertEquals(0L, rebasedVideoPts(100L, 200L, 50L))
    }
}
