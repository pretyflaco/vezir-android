package com.vezir.android.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide auth signal.
 *
 * When a token refresh fails definitively (no refresh token, or the server
 * rejects the refresh with 401 — idle/absolute expiry or a revoked family),
 * [markSessionExpired] flips [sessionExpired] to true.  The UI observes it
 * and routes the user back to the login screen.  A successful login clears
 * it via [clear].
 *
 * Kept as a plain object (not DI) to match the app's lightweight,
 * singleton-free style; there is exactly one auth session per process.
 */
object AuthState {
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired

    fun markSessionExpired() {
        _sessionExpired.value = true
    }

    fun clear() {
        _sessionExpired.value = false
    }
}
