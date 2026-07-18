package com.vezir.android.net

import com.vezir.android.BuildConfig
import com.vezir.android.auth.TokenRefresher
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
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

    // One base client per trust configuration (system CAs vs. system+custom
    // CA).  v0.8.0: `build` previously constructed a brand-new OkHttpClient
    // per call — its own connection pool, dispatcher, and TLS context per
    // API class — contradicting this object's documented contract.  Derived
    // clients via `newBuilder()` share the base's pool/dispatcher while
    // still allowing per-use timeouts.
    private val baseClients =
        java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    private fun baseClient(caPem: String?): OkHttpClient =
        baseClients.getOrPut(caPem?.hashCode()?.toString() ?: "system") {
            (caPem?.let { CaTrustManager.builderWithCa(it) }
                ?: OkHttpClient.Builder())
                .addInterceptor(UserAgentInterceptor)
                .build()
        }

    /**
     * Product User-Agent sent on every request.  vezir server >= 0.11.1
     * records this as the session's ``client_agent`` and surfaces it in
     * ``/api/sessions`` + TUI detail, so the desktop and Android clients
     * are distinguishable server-side.  Mirrors the Python clients'
     * ``vezir-cli/<version>`` convention with ``vezir-android/<version>``.
     */
    private val USER_AGENT = "vezir-android/${BuildConfig.VERSION_NAME}"

    /**
     * Adds our product [USER_AGENT] to every request, unless the caller
     * already set one explicitly.  Installed on the shared base client so
     * it survives all `newBuilder()`-derived per-use clients.
     */
    private object UserAgentInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            if (req.header("User-Agent") != null) return chain.proceed(req)
            return chain.proceed(
                req.newBuilder().header("User-Agent", USER_AGENT).build(),
            )
        }
    }

    /**
     * Build an [OkHttpClient] that trusts the system CAs plus an
     * optional custom CA from the enrollment QR payload.
     *
     * Derived from a shared base client, so the connection pool,
     * dispatcher, and TLS context are reused across all API classes and
     * URL-failover attempts in [ResilientApi].
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
        val builder = baseClient(caPem).newBuilder()
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
