package com.vezir.android.capture

import android.content.Context
import android.net.Uri
import com.vezir.android.audio.AudioImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide singleton driving the import-from-existing-recording
 * flow: SAF picker yields a content URI, [AudioImporter] either does an
 * OGG passthrough or a full transcode, and the resulting OGG lands in
 * `Music/Vezir/`. The UI then routes the user into [UploadScreen] for
 * the produced URI.
 */
object ImportController {

    enum class State { IDLE, IMPORTING, DONE, ERROR }

    data class Snapshot(
        val state: State = State.IDLE,
        val sourceUri: Uri? = null,
        val sourceName: String? = null,
        val progress: Float = 0f,
        val resultUri: Uri? = null,
        val resultDisplayName: String? = null,
        val resultDisplayPath: String? = null,
        val resultBytes: Long = 0L,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        job = null
        _state.value = Snapshot()
    }

    /** Acknowledge a finished/error snapshot without cancelling anything. */
    fun acknowledge() {
        _state.value = Snapshot()
    }

    /** Kick off the import; cancels any in-flight job. */
    fun startImport(context: Context, sourceUri: Uri, sourceName: String?) {
        job?.cancel()
        _state.value = Snapshot(
            state = State.IMPORTING,
            sourceUri = sourceUri,
            sourceName = sourceName,
        )
        // Use applicationContext so we don't hold an Activity reference
        // across configuration changes.
        val appCtx = context.applicationContext
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AudioImporter(appCtx).import(sourceUri) { fraction ->
                        _state.value = _state.value.copy(progress = fraction)
                    }
                }
                _state.value = _state.value.copy(
                    state = State.DONE,
                    progress = 1f,
                    resultUri = result.outputUri,
                    resultDisplayName = result.displayName,
                    resultDisplayPath = result.displayPath,
                    resultBytes = result.bytesWritten,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    state = State.ERROR,
                    errorMessage = t.message ?: t.javaClass.simpleName,
                )
            }
        }
    }
}
