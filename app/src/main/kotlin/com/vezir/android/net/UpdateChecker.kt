package com.vezir.android.net

import android.util.Log
import com.vezir.android.BuildConfig
import com.vezir.android.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Nudges the user when a newer APK is published on GitHub Releases — the
 * Android analog of the desktop TUI's PyPI update check (vezir 0.12.0,
 * vezir/client/tui/update_check.py).
 *
 * There is no in-app self-update: the app is sideloaded from a signed APK
 * attached to each GitHub Release, so the nudge just points the user at the
 * releases page.
 *
 * Polling is throttled to once per [CHECK_INTERVAL_MS] and the result is
 * cached in [Prefs] so the banner survives offline between checks.  Uses
 * GitHub's ``releases/latest`` endpoint, which excludes drafts and
 * prereleases — a stable-channel nudge only.  No auth token is needed for
 * this public, read-only endpoint (subject to GitHub's unauthenticated
 * rate limit, which the once-per-6h throttle stays well under).
 *
 * Disable at build time by omitting it, or at runtime the nudge is
 * suppressed once the user dismisses the offered tag.
 */
object UpdateChecker {

    private const val TAG = "VezirUpdateChecker"
    private const val LATEST_URL =
        "https://api.github.com/repos/pretyflaco/vezir-android/releases/latest"

    /** Minimum spacing between network checks (~6h, matching the TUI). */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** A pending update the UI should surface. */
    data class Available(val tag: String, val htmlUrl: String?)

    private val client: OkHttpClient by lazy {
        // Plain client: public unauthenticated endpoint, no team headers,
        // no refresh-on-401 (there is no auth here).
        HttpClients.build(caPem = null, connectTimeoutSec = 10, readTimeoutSec = 10, refreshOn401 = false)
    }

    /**
     * Check GitHub for a newer release, honoring the throttle and cache.
     * Returns a non-null [Available] when a newer, non-dismissed version
     * exists (from a fresh fetch or the cache), else null.  Safe to call on
     * every foreground; the network hit happens at most once per interval.
     */
    suspend fun check(prefs: Prefs): Available? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val due = now - prefs.updateLastCheckMs >= CHECK_INTERVAL_MS
        if (due) {
            runCatching { fetchLatest() }
                .onSuccess { latest ->
                    prefs.updateLastCheckMs = now
                    if (latest != null && isNewer(latest.tag, BuildConfig.VERSION_NAME)) {
                        prefs.updateLatestTag = latest.tag
                        prefs.updateLatestUrl = latest.htmlUrl
                    } else {
                        prefs.updateLatestTag = null
                        prefs.updateLatestUrl = null
                    }
                }
                .onFailure { e ->
                    // Network/parse failure: keep the cache, don't reset the
                    // timer aggressively — retry on the next foreground.
                    Log.d(TAG, "update check failed: ${e.message}")
                    prefs.updateLastCheckMs = now
                }
        }
        val cachedTag = prefs.updateLatestTag ?: return@withContext null
        if (cachedTag == prefs.updateDismissedTag) return@withContext null
        if (!isNewer(cachedTag, BuildConfig.VERSION_NAME)) return@withContext null
        Available(cachedTag, prefs.updateLatestUrl)
    }

    private fun fetchLatest(): Available? {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("GitHub releases HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: return null
            val o = JSONObject(body)
            if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) {
                return null
            }
            val tag = o.optString("tag_name", "").ifBlank { return null }
            val url = o.optString("html_url", "").ifBlank { null }
            return Available(tag, url)
        }
    }

    /**
     * True when [latestTag] is a strictly greater semantic version than
     * [current].  Both may carry a leading "v".  Non-numeric / malformed
     * inputs compare as "not newer" (fail safe: never nag on garbage).
     */
    fun isNewer(latestTag: String, current: String): Boolean {
        val a = parse(latestTag) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(raw: String): List<Int>? {
        val core = raw.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty()) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }
}
