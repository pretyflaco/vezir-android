package com.vezir.android.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared [OkHttpClient] factory.
 *
 * All API classes should use [build] (or the convenience overloads)
 * instead of constructing their own clients.  This ensures the
 * connection pool, dispatcher, and TLS context are reused across
 * URL failover attempts in [ResilientApi].
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
}
