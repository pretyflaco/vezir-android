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

    @Test
    fun `video template string selects the template`() {
        assertEquals("video-clipping", Prefs.summaryTemplateFor("talk.mp4", Prefs.VIDEO_CLIPPING))
        assertEquals("iteration-plan", Prefs.summaryTemplateFor("demo.mov", Prefs.ITERATION_PLAN))
        assertNull(Prefs.summaryTemplateFor("talk.mp4", ""))
        assertNull(Prefs.summaryTemplateFor("podcast.ogg", Prefs.VIDEO_CLIPPING))
    }

    @Test
    fun `offered templates and labels`() {
        assertEquals(
            listOf("iteration-plan", "video-clipping", ""),
            Prefs.VIDEO_TEMPLATE_OPTIONS.map { it.first },
        )
        assertEquals("video-clipping", Prefs.offeredVideoTemplateOr("video-clipping"))
        assertEquals("", Prefs.offeredVideoTemplateOr(""))
        assertEquals("iteration-plan", Prefs.offeredVideoTemplateOr("retired-template"))
        assertEquals("iteration-plan", Prefs.offeredVideoTemplateOr(null))
        assertEquals(
            "Clip highlights (short + long clips)",
            Prefs.videoTemplateLabelFor("video-clipping"),
        )
    }
}
