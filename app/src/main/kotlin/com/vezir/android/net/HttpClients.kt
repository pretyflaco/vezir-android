package com.vezir.android.net

import com.vezir.android.auth.TokenRefresher
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.TimeUnit

/**
 * Shared [OkHttpClient] factory + auth-header helper.
 *
 * All API classes should use [build] (or the convenience overloads)
 * instead of constructing their own clients.  This ensures the
 * connection pool, dispatcher, and TLS context are reused across
 * URL failover attempts in [ResilientApi].
 *
 * The [authHeaders] helper centralises the Authorization + X-Team-Id
 * header pair so every API class adds them consistently.  v0.5.0:
 * vezir server v0.7.0 requires X-Team-Id on every team-scoped
 * endpoint; missing -> 400, non-member -> 403.
 */
object HttpClients {

    /**
     * Build an [OkHttpClient] that trusts the system CAs plus an
     * optional custom CA from the enrollment QR payload.
     *
     * @param caPem PEM-encoded CA certificate, or null for system-only trust.
     * @param connectTimeoutSec TCP connect timeout in seconds.
     * @param readTimeoutSec    Socket read timeout in seconds.
     */
    fun build(
        caPem: String?,
        connectTimeoutSec: Long = 15,
        readTimeoutSec: Long = 30,
        refreshOn401: Boolean = true,
    ): OkHttpClient {
        val builder = caPem?.let { CaTrustManager.builderWithCa(it) }
            ?: OkHttpClient.Builder()
        builder
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        if (refreshOn401) {
            builder.authenticator(RefreshAuthenticator)
        }
        return builder.build()
    }

    /**
     * OkHttp [Authenticator] that, on a 401 to a Bearer-authenticated
     * request, transparently rotates the session via [TokenRefresher] and
     * retries the request once with the new access token.
     *
     * Guards:
     *  * Only acts on requests that carried an `Authorization: Bearer`
     *    header (login/refresh calls don't, so they never recurse).
     *  * Retries at most once (`responseCount` check) — a second 401 means
     *    the fresh token was also rejected, so we give up (return null) and
     *    let the 401 surface; [TokenRefresher] has already flipped
     *    [com.vezir.android.auth.AuthState] to route to login.
     *  * If refresh yields the same token (or none), gives up to avoid a
     *    request loop.
     */
    private object RefreshAuthenticator : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            val failed = response.request
            val header = failed.header("Authorization") ?: return null
            if (!header.startsWith("Bearer ")) return null
            if (responseCount(response) >= 2) return null  // already retried once

            val oldToken = header.removePrefix("Bearer ").trim()
            val newToken = runBlocking { TokenRefresher.refresh(oldToken) }
            if (newToken.isNullOrEmpty() || newToken == oldToken) return null

            return failed.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
        }

        private fun responseCount(response: Response): Int {
            var count = 1
            var prior = response.priorResponse
            while (prior != null) {
                count++
                prior = prior.priorResponse
            }
            return count
        }
    }

    /**
     * Add ``Authorization: Bearer <token>`` and (when non-null)
     * ``X-Team-Id: <teamId>`` headers to a request builder.
     *
     * Returns the same builder for fluent chaining.  Call sites:
     *
     *     Request.Builder()
     *         .url(...)
     *         .let { HttpClients.authHeaders(it, token, teamId) }
     *         .get()
     *         .build()
     *
     * Server v0.7.0 requires X-Team-Id on team-scoped endpoints
     * (/api/sessions, /upload, /api/label, /artifact, etc).  /api/me
     * and /health are the only routes that ignore it; callers hitting
     * those may pass ``teamId = null``.
     */
    fun authHeaders(
        builder: Request.Builder,
        token: String,
        teamId: String?,
    ): Request.Builder {
        builder.header("Authorization", "Bearer $token")
        if (!teamId.isNullOrBlank()) {
            builder.header("X-Team-Id", teamId)
        }
        return builder
    }
}
