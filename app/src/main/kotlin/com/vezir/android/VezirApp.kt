package com.vezir.android

import android.app.Application
import android.util.Log
import com.vezir.android.audio.OpusFinalizer
import com.vezir.android.capture.CaptureController
import com.vezir.android.capture.CaptureService
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
        Thread(Runnable { recoverInterruptedRecordings() }, "vezir-recovery").start()
        LabelCheckWorker.enqueue(this)
    }

    private fun recoverInterruptedRecordings() {
        val dir = File(filesDir, "recordings")
        dir.listFiles { file -> file.name.endsWith(".journal") }?.forEach { journalFile ->
            val finalizer = OpusFinalizer(journalFile)
            if (finalizer.completionFile.isFile) {
                journalFile.delete()
                finalizer.tempFile.delete()
                File(finalizer.completionFile.path + ".tmp").delete()
                return@forEach
            }
            try {
                val result = finalizer.recover(this)
                CaptureController.updateIfIdle {
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
