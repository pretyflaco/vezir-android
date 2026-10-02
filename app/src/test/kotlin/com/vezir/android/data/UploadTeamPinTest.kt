package com.vezir.android.data

import com.vezir.android.capture.CaptureController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v0.15.0: the destination team belongs to the recording.
 *
 * Incident 2026-10-02 (desktop vezir): the upload went to whichever team
 * was ACTIVE when it ran, so switching teams mid-recording — or before a
 * WorkManager retry — silently redirected the meeting.  The upload now
 * resolves the credential of the team pinned at record time and never
 * falls back to the active team when that one was pinned.
 */
class UploadTeamPinTest {

    private class FakeBacking : TeamCredentialBacking {
        override var teamsJson: String? = null
        override var activeTeamId: String? = null
    }

    private fun store(): TeamCredentialStore {
        val s = TeamCredentialStore(FakeBacking())
        s.addOrUpdate(TeamCredential("blink", "https://s", "tok"), activate = true)
        s.addOrUpdate(TeamCredential("twentyone", "https://s", "tok"))
        return s
    }

    @Test
    fun pinnedTeamWinsOverActiveTeam() {
        val s = store()
        // Recording pinned to twentyone; user then switches the app to blink.
        s.setActiveId("blink")
        assertEquals("twentyone", s.forUpload("twentyone")!!.id)
    }

    @Test
    fun unpinnedUploadUsesActiveTeam() {
        val s = store()
        s.setActiveId("twentyone")
        assertEquals("twentyone", s.forUpload(null)!!.id)
    }

    @Test
    fun pinnedTeamNoLongerEnrolledIsNullNotActive() {
        // Never silently redirect to the active team: fail loudly instead.
        val s = store()
        assertNull(s.forUpload("startups"))
    }

    @Test
    fun captureDestinationIsPinnedAndClearedOnAcknowledge() {
        CaptureController.pinDestination("blink")
        CaptureController.setDestination("twentyone")
        assertEquals("twentyone", CaptureController.destination.value)
        CaptureController.acknowledgeFinished()
        assertNull(CaptureController.destination.value)
    }
}
