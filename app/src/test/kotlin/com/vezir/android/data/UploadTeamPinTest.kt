package com.vezir.android.data

import com.vezir.android.capture.CaptureController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v0.15.0: the destination team belongs to the recording.
 *
 * In desktop vezir, the upload went to whichever team
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
        s.addOrUpdate(TeamCredential("team-a", "https://s", "tok"), activate = true)
        s.addOrUpdate(TeamCredential("team-b", "https://s", "tok"))
        return s
    }

    @Test
    fun pinnedTeamWinsOverActiveTeam() {
        val s = store()
        // Recording pinned to team-b; user then switches the app to team-a.
        s.setActiveId("team-a")
        assertEquals("team-b", s.forUpload("team-b")!!.id)
    }

    @Test
    fun unpinnedUploadUsesActiveTeam() {
        val s = store()
        s.setActiveId("team-b")
        assertEquals("team-b", s.forUpload(null)!!.id)
    }

    @Test
    fun pinnedTeamNoLongerEnrolledIsNullNotActive() {
        // Never silently redirect to the active team: fail loudly instead.
        val s = store()
        assertNull(s.forUpload("startups"))
    }

    @Test
    fun captureDestinationIsPinnedAndClearedOnAcknowledge() {
        CaptureController.pinDestination("team-a")
        CaptureController.setDestination("team-b")
        assertEquals("team-b", CaptureController.destination.value)
        CaptureController.acknowledgeFinished()
        assertNull(CaptureController.destination.value)
    }
}
