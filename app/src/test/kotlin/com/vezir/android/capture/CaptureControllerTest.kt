package com.vezir.android.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureControllerTest {
    @Test
    fun recoveryCannotOverwriteAnActiveCapture() {
        CaptureController.update {
            it.copy(state = CaptureController.State.RECORDING, displayName = "live")
        }
        CaptureController.updateIfIdle {
            it.copy(state = CaptureController.State.FINISHED, displayName = "recovered")
        }
        assertEquals(CaptureController.State.RECORDING, CaptureController.state.value.state)
        assertEquals("live", CaptureController.state.value.displayName)
    }

    @Test
    fun recoveryCanPublishWhileIdle() {
        CaptureController.update { it.copy(state = CaptureController.State.IDLE) }
        CaptureController.updateIfIdle {
            it.copy(state = CaptureController.State.FINISHED, recovered = true)
        }
        assertEquals(CaptureController.State.FINISHED, CaptureController.state.value.state)
        assertEquals(true, CaptureController.state.value.recovered)
    }
}
