package com.vezir.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryTemplateTest {

    @Test
    fun `mp4 gets the iteration plan when enabled`() {
        assertEquals(
            "iteration-plan",
            Prefs.summaryTemplateFor("vezir-screen-20260910-120000.mp4", true),
        )
    }

    @Test
    fun `mov gets the iteration plan when enabled`() {
        assertEquals("iteration-plan", Prefs.summaryTemplateFor("demo.mov", true))
    }

    @Test
    fun `audio never gets a template`() {
        assertNull(Prefs.summaryTemplateFor("meeting.ogg", true))
        assertNull(Prefs.summaryTemplateFor("meeting.wav", true))
    }

    @Test
    fun `disabled toggle yields no template`() {
        assertNull(Prefs.summaryTemplateFor("demo.mp4", false))
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertEquals("iteration-plan", Prefs.summaryTemplateFor("DEMO.MP4", true))
    }
}
