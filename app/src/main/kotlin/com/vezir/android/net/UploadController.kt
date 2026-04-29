package com.vezir.android.net

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide upload + status-poll state. The UI subscribes; the upload
 * coroutine writes here on each progress callback / status change.
 *
 * v1 supports one upload at a time. Re-launching while an upload is in
 * flight cancels the previous job.
 */
object UploadController {

    enum class State { IDLE, UPLOADING, POLLING, DONE, ERROR }

    data class Snapshot(
        val state: State = State.IDLE,
        val sessionId: String? = null,
        val sentBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val attempt: Int = 1,
        val maxAttempts: Int = 3,
        val serverStatus: String? = null,         // queued | transcribing | needs_labeling | syncing | done | error
        val serverError: String? = null,
        val errorMessage: String? = null,         // upload-side error
        val dashboardUrl: String? = null,
        val dashboardLoginUrl: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** Cancel any in-flight upload/poll and reset to IDLE. */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = Snapshot()
    }

    /**
     * Kick an upload (and a follow-up poller on success). Idempotent if a
     * different session is already in flight: the current job is cancelled.
     */
    fun startUpload(
        baseUrl: String,
        token: String,
        contentResolver: ContentResolver,
        contentUri: Uri,
        fileName: String,
        title: String?,
    ) {
        job?.cancel()
        _state.value = Snapshot(state = State.UPLOADING)
        job = scope.launch {
            val uploader = Uploader(baseUrl, token, contentResolver)
            val outcome = uploader.upload(
                contentUri = contentUri,
                fileName = fileName,
                title = title,
                progress = { sent, total ->
                    _state.value = _state.value.copy(
                        state = State.UPLOADING,
                        sentBytes = sent,
                        totalBytes = total,
                    )
                },
                onRetry = { attempt, max, _ ->
                    _state.value = _state.value.copy(
                        attempt = attempt + 1, // we're about to attempt the next
                        maxAttempts = max,
                        sentBytes = 0L,        // server restarts upload from byte 0
                    )
                },
            )
            when (outcome) {
                is Uploader.Outcome.Success -> {
                    _state.value = _state.value.copy(
                        state = State.POLLING,
                        sessionId = outcome.response.session_id,
                        sentBytes = outcome.response.bytes,
                        totalBytes = outcome.response.bytes,
                        dashboardUrl = outcome.response.dashboard_url,
                        dashboardLoginUrl = outcome.response.dashboard_login_url,
                    )
                    pollForStatus(baseUrl, token, outcome.response.session_id)
                }
                is Uploader.Outcome.HttpError -> {
                    _state.value = _state.value.copy(
                        state = State.ERROR,
                        errorMessage = "HTTP ${outcome.code}: ${outcome.message}",
                    )
                }
                is Uploader.Outcome.Failed -> {
                    _state.value = _state.value.copy(
                        state = State.ERROR,
                        errorMessage = outcome.cause.message ?: outcome.cause.toString(),
                    )
                }
            }
        }
    }

    private suspend fun pollForStatus(baseUrl: String, token: String, sessionId: String) {
        val poller = SessionPoller(baseUrl, token)
        poller.poll(sessionId).collect { status ->
            val terminal = status.status == "done" || status.status == "error"
            _state.value = _state.value.copy(
                state = if (terminal) State.DONE else State.POLLING,
                serverStatus = status.status,
                serverError = status.error,
            )
        }
        // Flow completed on terminal status. If the loop exited without a
        // terminal hit (e.g. cancellation), leave the snapshot as-is.
    }
}
