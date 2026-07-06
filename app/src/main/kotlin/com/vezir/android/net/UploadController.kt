package com.vezir.android.net

import android.content.Context
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
 * Process-wide upload + status-poll state. The UI subscribes; the
 * [UploadWorker] writes here on each progress callback / status change.
 *
 * v0.8.0: the upload itself moved into [UploadWorker] (foreground
 * WorkManager work with a persisted tus session), so it survives
 * backgrounding and process death.  This object is now a thin layer:
 * [startUpload] enqueues the worker, the worker publishes progress into
 * the snapshot, and the post-upload status poll (UI sugar; cheap) runs
 * here in-process.
 *
 * v1 supports one upload at a time. Re-launching while an upload is in
 * flight replaces the queued work.
 */
object UploadController {

    /** User-facing message for an expired/invalid session (HTTP 401). */
    const val SESSION_EXPIRED_MESSAGE = "Session expired \u2014 please sign in again"

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
        val summaryError: String? = null,         // summary-only failure (transcript OK)
        val syncError: String? = null,            // sync-only failure (artifacts OK)
        val errorMessage: String? = null,         // upload-side error
        /** True when the last retry restarted from byte 0 (legacy path). */
        val restartedFromZero: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    /** Cancel any in-flight upload/poll and reset to IDLE. */
    fun reset(context: Context? = null) {
        pollJob?.cancel()
        pollJob = null
        context?.let { UploadWorker.cancel(it) }
        _state.value = Snapshot()
    }

    /**
     * Force an ERROR state from outside the worker — used by the UI to
     * short-circuit a doomed upload when the session JWT is already
     * expired, before any bytes leave the device.
     */
    fun setError(message: String) {
        pollJob?.cancel()
        pollJob = null
        _state.value = Snapshot(state = State.ERROR, errorMessage = message)
    }

    /**
     * Enqueue the upload as unique foreground work (and reset the
     * snapshot to UPLOADING).  Credentials are read by the worker from
     * encrypted prefs — they never ride through WorkManager's Data.
     */
    fun startUpload(
        context: Context,
        baseUrl: String,
        contentUri: Uri,
        fileName: String,
        title: String?,
        summaryPreset: String? = null,
        autoLabel: Boolean = true,
        sync: Boolean = true,
        personal: Boolean = false,
    ) {
        pollJob?.cancel()
        pollJob = null
        _state.value = Snapshot(state = State.UPLOADING)
        UploadWorker.enqueue(
            context, baseUrl, contentUri, fileName, title,
            summaryPreset, autoLabel, sync, personal,
        )
    }

    // ── Worker-facing publishers ────────────────────────────────────────

    internal fun publishProgress(sent: Long, total: Long) {
        _state.value = _state.value.copy(
            state = State.UPLOADING, sentBytes = sent, totalBytes = total,
        )
    }

    internal fun publishRetry(attempt: Int, max: Int, restarted: Boolean = false) {
        _state.value = _state.value.copy(
            state = State.UPLOADING,
            attempt = attempt,
            maxAttempts = max,
            restartedFromZero = restarted,
            // The resumable path resumes from the server offset; only the
            // legacy path restarts from byte 0.
            sentBytes = if (restarted) 0L else _state.value.sentBytes,
        )
    }

    internal fun publishError(message: String) {
        _state.value = _state.value.copy(
            state = State.ERROR, errorMessage = message,
        )
    }

    internal fun publishSuccess(
        baseUrl: String,
        token: String,
        teamId: String?,
        sessionId: String,
        bytes: Long,
        caPem: String?,
    ) {
        _state.value = _state.value.copy(
            state = State.POLLING, sessionId = sessionId,
            sentBytes = bytes, totalBytes = bytes,
        )
        pollJob?.cancel()
        pollJob = scope.launch {
            pollForStatus(baseUrl, token, teamId, sessionId, caPem)
        }
    }

    private suspend fun pollForStatus(
        baseUrl: String, token: String, teamId: String?,
        sessionId: String, caPem: String? = null,
    ) {
        val poller = SessionPoller(baseUrl, token, teamId, caPem)
        try {
            poller.poll(sessionId).collect { status ->
                val terminal = status.status == "done" || status.status == "error"
                _state.value = _state.value.copy(
                    state = if (terminal) State.DONE else State.POLLING,
                    serverStatus = status.status,
                    serverError = status.error,
                    summaryError = status.summary_error,
                    syncError = status.sync_error,
                )
            }
        } catch (_: SessionPoller.AuthExpiredException) {
            // Upload succeeded, but the session expired before processing
            // finished. Surface it instead of polling forever; the
            // artifacts are safe on the server and reachable after
            // re-login via the Sessions tab / `vezir pull`.
            _state.value = _state.value.copy(
                state = State.ERROR,
                errorMessage = SESSION_EXPIRED_MESSAGE,
            )
        }
        // Flow completed on terminal status. If the loop exited without a
        // terminal hit (e.g. cancellation), leave the snapshot as-is.
    }
}
