package com.vezir.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Multi-team credential store.
 *
 * Each enrolled team has its own [TeamCredential] entry holding server
 * URL, bearer token, CA cert (optional), and cached `/api/me` metadata.
 * One team is marked active at a time; all API calls use the active
 * credential.
 *
 * Persisted as JSON inside [Prefs]'s EncryptedSharedPreferences under
 * two keys: `teams_json` (the list) and `active_team_id` (the slug).
 */
@Serializable
data class TeamCredential(
    val id: String,           // team slug, e.g. "blink"
    val url: String,          // server URL
    val token: String,        // bearer token / session access JWT
    val caPem: String? = null,// PEM CA cert from v2 enrollment
    val label: String = "",   // human-readable team name from /api/me
    val github: String? = null, // handle from /api/me
    val isAdmin: Boolean = false,
    val altUrls: List<String> = emptyList(),  // alternate server URLs for failover
    // Rotating refresh-token session (vezir server >= 0.10.0).  All team
    // entries created from one login share the same refresh token, since a
    // server session is one family per login (not per team).  Null for
    // legacy `vzr_` tokens and pre-0.7.0 logins; defaults keep older
    // persisted JSON deserializable.
    val refreshToken: String? = null,
    val accessExpiresAt: Long = 0,  // epoch seconds; 0 = unknown
)

/**
 * Minimal persistence seam the [TeamCredentialStore] needs.
 *
 * [Prefs] implements this over EncryptedSharedPreferences; tests supply a
 * plain in-memory fake so the multi-team / refresh logic is unit-testable
 * without Android (no Robolectric).
 */
interface TeamCredentialBacking {
    var teamsJson: String?
    var activeTeamId: String?
}

/**
 * In-memory + serialized team credential list with an active selection.
 */
class TeamCredentialStore(private val prefs: TeamCredentialBacking) {

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    /** Load all team credentials from encrypted prefs. */
    fun loadAll(): List<TeamCredential> {
        val raw = prefs.teamsJson ?: return emptyList()
        return try {
            json.decodeFromString<List<TeamCredential>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Save the full list back to encrypted prefs. */
    fun saveAll(teams: List<TeamCredential>) {
        prefs.teamsJson = json.encodeToString(teams)
    }

    /**
     * Apply a rotated access/refresh pair to every team entry that
     * currently shares [oldToken].
     *
     * A vezir session is one family per login shared across all of a
     * user's teams, so a single refresh must update every entry that was
     * carrying the just-expired access token.  Entries with a different
     * token (e.g. a separate `vzr_`-token team) are left untouched.
     *
     * Returns the number of entries updated.
     */
    fun applyRefreshedToken(
        oldToken: String,
        newToken: String,
        newRefreshToken: String?,
        accessExpiresAt: Long,
    ): Int {
        val teams = loadAll()
        var updated = 0
        val next = teams.map { t ->
            if (t.token == oldToken) {
                updated++
                t.copy(
                    token = newToken,
                    refreshToken = newRefreshToken ?: t.refreshToken,
                    accessExpiresAt = accessExpiresAt,
                )
            } else {
                t
            }
        }
        if (updated > 0) saveAll(next)
        return updated
    }

    /** Get the active team id. */
    fun activeId(): String? = prefs.activeTeamId

    /** Set the active team id. */
    fun setActiveId(id: String) {
        prefs.activeTeamId = id
    }

    /** Get the active credential, or null if none configured. */
    fun getActive(): TeamCredential? {
        val teams = loadAll()
        val activeId = activeId()
        return teams.firstOrNull { it.id == activeId }
            ?: teams.firstOrNull() // fallback to first if active is stale
    }

    /** True if multi-team credentials are configured. */
    fun isConfigured(): Boolean = loadAll().isNotEmpty()

    /**
     * Add or update a team credential. If [activate] is true, or this
     * is the only entry, it becomes the active team.
     */
    fun addOrUpdate(credential: TeamCredential, activate: Boolean = false) {
        val teams = loadAll().toMutableList()
        val idx = teams.indexOfFirst { it.id == credential.id }
        if (idx >= 0) {
            teams[idx] = credential
        } else {
            teams.add(credential)
        }
        saveAll(teams)
        if (activate || teams.size == 1) {
            setActiveId(credential.id)
        }
    }

    /** Remove a team credential. Returns false if it's the only one. */
    fun remove(id: String): Boolean {
        val teams = loadAll().toMutableList()
        if (teams.size <= 1) return false
        teams.removeAll { it.id == id }
        saveAll(teams)
        // If we removed the active team, switch to the first remaining.
        if (activeId() == id && teams.isNotEmpty()) {
            setActiveId(teams.first().id)
        }
        return true
    }

    /**
     * Cycle to the next team and return it, or null if only one team.
     * Wraps around at the end.
     */
    fun cycleNext(): TeamCredential? {
        val teams = loadAll()
        if (teams.size <= 1) return null
        val currentIdx = teams.indexOfFirst { it.id == activeId() }
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % teams.size
        val next = teams[nextIdx]
        setActiveId(next.id)
        return next
    }
}
