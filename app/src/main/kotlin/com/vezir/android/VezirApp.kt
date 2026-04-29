package com.vezir.android

import android.app.Application
import com.vezir.android.capture.CaptureService

/**
 * Application class. Process-wide init hook.
 *
 * Currently:
 *   - Pre-creates the capture-service notification channel so the channel
 *     exists before [CaptureService] tries to call startForeground(),
 *     even though the service also creates it defensively in onCreate().
 */
class VezirApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CaptureService.ensureNotificationChannel(this)
    }
}
