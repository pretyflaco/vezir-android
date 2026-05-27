package com.vezir.android.net

import okhttp3.OkHttpClient
import okhttp3.Request
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
    ): OkHttpClient {
        val builder = caPem?.let { CaTrustManager.builderWithCa(it) }
            ?: OkHttpClient.Builder()
        return builder
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .build()
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
