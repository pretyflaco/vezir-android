package com.vezir.android

import android.app.Application

/**
 * Application class. Used as a process-wide hook for things M2/M3 will need
 * (notification channels for the capture foreground service, the OkHttp
 * client singleton, etc.). Empty for M1.
 */
class VezirApp : Application()
