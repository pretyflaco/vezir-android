package com.vezir.android.auth

import android.util.Log
import com.vezir.android.data.TeamCredential
import com.vezir.android.data.TeamCredentialStore
import com.vezir.android.net.MeApi
import com.vezir.android.net.SessionApi

/**
 * Shared post-authentication step: given a freshly obtained
 * (url, token, caPem) — from QR/token enrollment, nostr sign-in, or
 * Google sign-in — call `/api/me`, create one [TeamCredential] per team
 * membership (all sharing the token), and activate the first.
 *
 * This is the single place that turns "I have a bearer token" into the
 * app's multi-team credential store, so every sign-in method converges
 * on the same code path (mirrors the original inline block in
 * MainActivity.onConfigured).
 */
object SessionDiscovery {

    data class Outcome(val activeLabel: String?, val teamCount: Int)

    /**
     * Returns the active team label on success, or null if `/api/me` had
     * no memberships / failed (caller can surface an error).
     */
    suspend fun discoverAndStore(
        store: TeamCredentialStore,
        url: String,
        token: String,
        caPem: String? = null,
        refreshToken: String? = null,
        accessExpiresIn: Long = 0,
    ): Outcome {
        val api = MeApi(url, token, caPem)
        val result = api.getMe()
        if (result !is SessionApi.Result.Ok) {
            Log.w("Vezir", "session discovery: /api/me failed")
            return Outcome(null, 0)
        }
        val me = result.data
        if (me.memberships.isEmpty()) {
            Log.w("Vezir", "session discovery: identity has no team memberships")
            return Outcome(null, 0)
        }
        val accessExpiresAt =
            if (accessExpiresIn > 0) System.currentTimeMillis() / 1000 + accessExpiresIn else 0
        val refresh = refreshToken?.ifEmpty { null }
        me.memberships.forEachIndexed { idx, mem ->
            store.addOrUpdate(
                TeamCredential(
                    id = mem.team_id,
                    url = url,
                    token = token,
                    caPem = caPem,
                    label = mem.team_name,
                    github = me.github,
                    isAdmin = me.is_admin,
                    altUrls = me.alternate_urls,
                    refreshToken = refresh,
                    accessExpiresAt = accessExpiresAt,
                ),
                activate = (idx == 0),
            )
        }
        val first = me.memberships.first()
        return Outcome(first.team_name, me.memberships.size)
    }
}
