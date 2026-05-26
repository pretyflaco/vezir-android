package com.vezir.android.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * URL-failover wrapper around [SessionApi].
 *
 * When the primary server URL is unreachable (e.g., nvpn tunnel down),
 * this class automatically probes alternate URLs via `/health` and
 * uses the first reachable one for the actual API call.
 *
 * Usage:
 * ```
 * val api = ResilientApi(primaryUrl, altUrls, token, caPem)
 * val result = api.execute { it.getSessions() }
 * ```
 *
 * The last successful URL is remembered ([lastGoodUrl]) and tried
 * first on subsequent calls, so there is no probe overhead when the
 * network is stable.
 */
class ResilientApi(
    private val primaryUrl: String,
    private val altUrls: List<String>,
    private val token: String,
    private val caPem: String?,
) {
    companion object {
        private const val TAG = "ResilientApi"
        private const val PROBE_TIMEOUT_SEC = 5L
    }

    private val urls: List<String> = buildList {
        add(primaryUrl)
        altUrls.forEach { if (it != primaryUrl) add(it) }
    }

    /** Shared OkHttpClient with standard timeouts for API calls. */
    val client: OkHttpClient = HttpClients.build(caPem)

    /** Short-timeout client for /health probes. */
    private val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** The URL that last succeeded. Tried first on the next call. */
    @Volatile
    var lastGoodUrl: String = primaryUrl
        private set

    /**
     * Probe `GET /health` on each URL (starting with [lastGoodUrl])
     * and return the first one that responds successfully, or null.
     */
    suspend fun findReachableUrl(): String? = withContext(Dispatchers.IO) {
        // Try last-good first, then the rest in order.
        val ordered = buildList {
            add(lastGoodUrl)
            urls.forEach { if (it != lastGoodUrl) add(it) }
        }
        for (url in ordered) {
            val healthUrl = "${url.trimEnd('/')}/health"
            val req = Request.Builder().url(healthUrl).get().build()
            try {
                probeClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Log.d(TAG, "probe OK: $url")
                        return@withContext url
                    }
                    Log.d(TAG, "probe HTTP ${resp.code}: $url")
                }
            } catch (e: IOException) {
                Log.d(TAG, "probe failed: $url (${e.message})")
                continue
            }
        }
        null
    }

    /**
     * Execute an API action with URL failover.
     *
     * 1. Probes `/health` to find a reachable URL.
     * 2. Executes the real API call on the winning URL.
     * 3. If the probe succeeds but the API call gets a [NetworkError],
     *    does NOT retry on other URLs (the server was reachable at
     *    probe time; the failure is likely transient).
     */
    suspend fun <T> execute(
        action: suspend (SessionApi) -> SessionApi.Result<T>,
    ): SessionApi.Result<T> {
        val reachableUrl = findReachableUrl()
        if (reachableUrl == null) {
            val msg = if (urls.size == 1) {
                "Server unreachable: ${urls[0]}"
            } else {
                "All server URLs unreachable: ${urls.joinToString()}"
            }
            return SessionApi.Result.NetworkError(IOException(msg))
        }
        lastGoodUrl = reachableUrl
        val api = SessionApi(reachableUrl, token, caPem = null, externalClient = client)
        return action(api)
    }
}
