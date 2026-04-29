package com.vezir.android.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Process-wide singleton that lets the UI observe what
 * [CaptureService] is doing and lets the service publish updates.
 *
 * State flow rather than service-bound IPC because:
 *   - everything lives in the same process (foreground service in :app)
 *   - Compose reads [StateFlow] cheaply
 *   - we avoid AIDL boilerplate that v1 doesn't need
 */
object CaptureController {

    enum class State { IDLE, STARTING, RECORDING, STOPPING, FINISHED, ERROR }

    data class Snapshot(
        val state: State = State.IDLE,
        val elapsedMs: Long = 0L,
        val bytesWritten: Long = 0L,
        val playbackRmsDbfs: Float = -90f,
        val micRmsDbfs: Float = -90f,
        val playbackSilent: Boolean = false,  // hint when only mic is audible
        val outputFile: File? = null,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Service-side update entry point. */
    fun update(transform: (Snapshot) -> Snapshot) {
        _state.value = transform(_state.value)
    }

    /** UI can call this after viewing a finished session to reset to IDLE. */
    fun acknowledgeFinished() {
        _state.value = Snapshot()
    }
}
