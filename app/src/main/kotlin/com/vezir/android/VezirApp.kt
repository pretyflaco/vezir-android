package com.vezir.android

import android.app.Application
import android.net.Uri
import android.util.Log
import com.vezir.android.audio.OpusFinalizer
import com.vezir.android.audio.OpusJournal
import com.vezir.android.capture.CaptureController
import com.vezir.android.capture.CaptureService
import com.vezir.android.capture.RecordingStorage
import com.vezir.android.net.LabelCheckWorker
import java.io.File

/**
 * Application class. Process-wide init hook.
 *
 *   - Pre-creates the capture-service notification channel.
 *   - Enqueues the periodic labeling-check background worker.
 */
class VezirApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CaptureService.ensureNotificationChannel(this)
        recoverInterruptedRecordings()
        LabelCheckWorker.enqueue(this)
    }

    private fun recoverInterruptedRecordings() {
        val dir = File(filesDir, "recordings")
        dir.listFiles { file -> file.name.endsWith(".journal") }?.forEach { journalFile ->
            val finalizer = OpusFinalizer(journalFile)
            if (finalizer.completionFile.isFile) {
                journalFile.delete()
                return@forEach
            }
            try {
                val metadata = OpusJournal.read(journalFile).metadata
                val uri = metadata.targetUri ?: error("journal has no target URI")
                val target = RecordingStorage.openExisting(
                    this,
                    Uri.parse(uri),
                    metadata.displayName,
                    metadata.displayPath ?: "Music/Vezir/${metadata.displayName}",
                    RecordingStorage.MIME_OGG,
                )
                val result = finalizer.recover(this, target)
                CaptureController.update {
                    it.copy(
                        state = CaptureController.State.FINISHED,
                        bytesWritten = result.bytesWritten,
                        outputUri = result.uri,
                        displayName = result.displayName,
                        displayPath = result.displayPath,
                        recovered = true,
                        stopReason = "Recovered recording — review before upload.",
                    )
                }
            } catch (t: Throwable) {
                Log.w("VezirRecovery", "could not recover $journalFile", t)
            }
        }
    }
}
