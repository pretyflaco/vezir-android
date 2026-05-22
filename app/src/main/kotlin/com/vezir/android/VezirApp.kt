package com.vezir.android

import android.app.Application
import com.vezir.android.capture.CaptureService
import com.vezir.android.net.LabelCheckWorker

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
        LabelCheckWorker.enqueue(this)
    }
}
