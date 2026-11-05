package com.vezir.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.15.0: session move response handling + team-keyed upload resume. */
class MoveAndResumeTest {

    @Test
    fun parsesMoveResponse() {
        val m = SessionApi.MoveParsing.parseMoved(
            """{"ok":true,"session_id":"01S","from_team":"team-a","to_team":"team-b",
               "moved":true,"was_synced":true,"sync_queued":false,
               "warning":"this session was already synced to team-a's git repo; that copy stays there — remove it from the repo manually."}""",
            requestedTeam = "team-b",
        )
        assertEquals("team-a", m.fromTeam)
        assertEquals("team-b", m.toTeam)
        assertTrue(m.moved && m.wasSynced)
        assertFalse(m.syncQueued)
        assertTrue(m.warning!!.contains("manually"))
    }

    @Test
    fun moveResponseToleratesMissingFields() {
        val m = SessionApi.MoveParsing.parseMoved("{}", requestedTeam = "team-b")
        assertEquals("team-b", m.toTeam)
        assertNull(m.warning)
    }

    @Test
    fun oldServerIsExplained_ourOwn404sAreNot() {
        assertTrue(
            SessionApi.MoveParsing.moveErrorMessage(404, "Not Found")
                .contains("too old"),
        )
        assertEquals(
            "session not found",
            SessionApi.MoveParsing.moveErrorMessage(404, "session not found"),
        )
        assertEquals(
            "a follow-up task is running",
            SessionApi.MoveParsing.moveErrorMessage(409, "a follow-up task is running"),
        )
    }

    @Test
    fun resumeStateIsKeyedByTeam() {
        val uri = "content://media/1"
        val url = "https://s"
        assertTrue(UploadStateStore.matches(uri, url, "team-a", uri, url, "team-a"))
        // Retargeted between attempts → never continue the old team's tus session.
        assertFalse(UploadStateStore.matches(uri, url, "team-a", uri, url, "team-b"))
        // State written by a pre-0.15.0 build (no team) never matches a pinned upload.
        assertFalse(UploadStateStore.matches(uri, url, null, uri, url, "team-a"))
    }
}
