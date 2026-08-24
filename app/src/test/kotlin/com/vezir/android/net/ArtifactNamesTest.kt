package com.vezir.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock the dated artifact filename logic (v0.10.0, mirroring desktop
 * vezir v0.14.1 `artifact_friendly_name` in vezir/config.py) against
 * regressions.
 */
class ArtifactNamesTest {

    @Test
    fun titleSlug_lowercasesAndUnderscores() {
        assertEquals("brainstorm_phoenix", artifactTitleSlug("Brainstorm Phoenix"))
        assertEquals("weekly_sync_blink", artifactTitleSlug("  Weekly Sync / @blink!  "))
    }

    @Test
    fun titleSlug_collapsesUnderscoreRuns() {
        assertEquals("a_b", artifactTitleSlug("a --- b"))
    }

    @Test
    fun titleSlug_capsAt60() {
        assertEquals(60, artifactTitleSlug("x".repeat(100)).length)
    }

    @Test
    fun titleSlug_blankIsEmpty() {
        assertEquals("", artifactTitleSlug(null))
        assertEquals("", artifactTitleSlug("   "))
    }

    @Test
    fun sessionDate_parsesUtcToLocalDate() {
        // Parsed in the device timezone; just lock the format and the
        // parse-not-today behavior for an unambiguous far-past timestamp.
        val d = ArtifactNames.sessionDate("2026-08-23T22:30:00Z")
        assertTrue(d.matches(Regex("\\d{8}")))
    }

    @Test
    fun sessionDate_unusableFallsBackToToday() {
        assertEquals(
            java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
            ArtifactNames.sessionDate("not-a-date"),
        )
        assertEquals(
            java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE),
            ArtifactNames.sessionDate(null),
        )
    }

    @Test
    fun friendlyName_allSuffixes() {
        val cases = listOf(
            "01ABC.summary.md" to ".md",
            "01ABC.frontmatter.json" to ".frontmatter.json",
            "01ABC.srt" to ".srt",
            "01ABC.txt" to ".txt",
            "01ABC.pdf" to ".pdf",
            "01ABC.json" to ".json",
        )
        for ((serverName, ext) in cases) {
            val name = ArtifactNames.friendlyName(
                serverName, "2026-08-24T10:00:00Z", "My Talk",
            )
            // Date part depends on the device timezone; assert the slug+ext.
            assertTrue("failed for $serverName: $name", name.endsWith("_my_talk$ext"))
            assertTrue(name.startsWith("20"))
        }
    }

    @Test
    fun friendlyName_noTitleFallsBackToRecording() {
        val name = ArtifactNames.friendlyName(
            "01ABC.pdf", "2026-08-24T10:00:00Z", null,
        )
        assertTrue(name.endsWith("_recording.pdf"))
    }

    @Test
    fun friendlyName_unknownTypeKeepsOriginal() {
        assertEquals(
            "slides.pdf.bak",
            ArtifactNames.friendlyName("slides.pdf.bak", "2026-08-24T10:00:00Z", "t"),
        )
    }

    @Test
    fun dispositionParser_quotedFilename() {
        assertEquals(
            "20260824_brainstorm_phoenix.pdf",
            SessionApi.filenameFromDisposition(
                "attachment; filename=\"20260824_brainstorm_phoenix.pdf\"",
            ),
        )
    }

    @Test
    fun dispositionParser_utf8FormWins() {
        assertEquals(
            "20260824_brain storm.pdf",
            SessionApi.filenameFromDisposition(
                "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''20260824_brain%20storm.pdf",
            ),
        )
    }

    @Test
    fun dispositionParser_bareAndMissing() {
        assertEquals("a.pdf", SessionApi.filenameFromDisposition("attachment; filename=a.pdf"))
        assertNull(SessionApi.filenameFromDisposition(null))
        assertNull(SessionApi.filenameFromDisposition("attachment"))
    }
}
