package com.vezir.android.auth

import android.util.Log
import com.vezir.android.data.TeamCredentialStore
import com.vezir.android.net.RefreshApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single-flight coordinator for rotating refresh-token renewal.
 *
 * Rotating refresh tokens are **single-use with server-side reuse
 * detection**: if two requests refreshed concurrently, the second would
 * present an already-consumed token and the server would revoke the whole
 * family, logging the user out.  So all refreshes funnel through one
 * [Mutex]; concurrent 401s coalesce onto a single network refresh.
 *
 * On success the rotated access + refresh tokens are written to **every**
 * team entry sharing the old access token (a vezir session is one family
 * per login, shared across the user's teams).
 *
 * On definitive failure (no refresh token, or the server returns 401) it
 * flips [AuthState] so the UI routes back to login.  A transient network
 * error returns false without tripping AuthState — the caller's original
 * request just fails and can be retried later.
 *
 * Must be [init]ialised once (from the Activity) before the OkHttp
 * [com.vezir.android.net.HttpClients] Authenticator can use it.
 */
object TokenRefresher {

    private val mutex = Mutex()

    @Volatile
    private var store: TeamCredentialStore? = null

    /** Wire the credential store. Idempotent; safe to call on each launch. */
    fun init(credentialStore: TeamCredentialStore) {
        store = credentialStore
    }

    /**
     * Attempt to refresh the token that [expiredToken] represents (the one
     * that just 401'd, or the active team's token when null).
     *
     * @return the new access token on success, or null on failure. On a
     *   definitive (non-network) failure it also marks the session expired.
     */
    suspend fun refresh(expiredToken: String? = null): String? = mutex.withLock {
        val store = this.store ?: run {
            Log.w("Vezir", "TokenRefresher used before init()")
            return@withLock null
        }

        val active = store.getActive() ?: return@withLock null
        val oldToken = expiredToken ?: active.token

        // Another caller may have already rotated while we waited on the
        // mutex.  If the active token no longer matches the one that
        // failed, that refresh already happened — hand back the fresh one.
        if (active.token != oldToken && active.token.isNotEmpty()) {
            Log.d("Vezir", "TokenRefresher: token already rotated by a prior caller")
            return@withLock active.token
        }

        val refreshToken = active.refreshToken
        if (refreshToken.isNullOrEmpty()) {
            // Legacy vzr_ token or pre-0.10.0 login: nothing to refresh.
            Log.d("Vezir", "TokenRefresher: no refresh token; session expired")
            AuthState.markSessionExpired()
            return@withLock null
        }

        val api = RefreshApi(active.url, active.caPem)
        when (val r = api.refresh(refreshToken)) {
            is RefreshApi.Result.Ok -> {
                val newAccess = r.data.accessToken()
                if (newAccess.isEmpty()) {
                    Log.w("Vezir", "TokenRefresher: refresh returned empty access token")
                    AuthState.markSessionExpired()
                    return@withLock null
                }
                val expiresAt =
                    if (r.data.expires_in > 0) {
                        System.currentTimeMillis() / 1000 + r.data.expires_in
                    } else {
                        0
                    }
                val n = store.applyRefreshedToken(
                    oldToken = oldToken,
                    newToken = newAccess,
                    newRefreshToken = r.data.refresh_token.ifEmpty { refreshToken },
                    accessExpiresAt = expiresAt,
                )
                Log.i("Vezir", "TokenRefresher: refreshed session across $n team(s)")
                newAccess
            }
            is RefreshApi.Result.HttpError -> {
                // 401 (and any other terminal HTTP status) means this
                // refresh token can't mint tokens anymore -> full re-login.
                Log.i("Vezir", "TokenRefresher: refresh rejected (${r.code}); session expired")
                AuthState.markSessionExpired()
                null
            }
            is RefreshApi.Result.NetworkError -> {
                // Transient: don't force a logout; let the caller fail and retry.
                Log.w("Vezir", "TokenRefresher: refresh network error: ${r.cause.message}")
                null
            }
        }
    }
}
