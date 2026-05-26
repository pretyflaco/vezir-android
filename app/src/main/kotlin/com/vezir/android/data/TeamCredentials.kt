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
    val token: String,        // bearer token
    val caPem: String? = null,// PEM CA cert from v2 enrollment
    val label: String = "",   // human-readable team name from /api/me
    val github: String? = null, // handle from /api/me
    val isAdmin: Boolean = false,
    val altUrls: List<String> = emptyList(),  // alternate server URLs for failover
)

/**
 * In-memory + serialized team credential list with an active selection.
 */
class TeamCredentialStore(private val prefs: Prefs) {

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
