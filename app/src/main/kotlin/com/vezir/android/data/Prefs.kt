package com.vezir.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest settings: VEZIR_URL and VEZIR_TOKEN.
 *
 * Backed by `androidx.security:security-crypto`'s
 * [EncryptedSharedPreferences], which wraps a SharedPreferences file with
 * AES-256-GCM, with the per-app master key managed by the Android
 * Keystore. The file is named `vezir_secure_prefs.xml` so backup-exclusion
 * rules in `xml/backup_rules.xml` and `xml/data_extraction_rules.xml` can
 * target it precisely.
 *
 * v0.8.0: use [Prefs.get] — one process-wide instance.  Multiple live
 * [EncryptedSharedPreferences] instances over the same file are a
 * documented corruption footgun (and each construction runs Keystore
 * init, previously up to three times, once on the main thread in
 * `onCreate`).  The constructor stays public only for the [get]
 * factory; new call sites must not construct directly.
 */
class Prefs(context: Context) : TeamCredentialBacking {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var serverUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_URL) else putString(KEY_URL, value)
            }.apply()
        }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
            }.apply()
        }

    /**
     * Summarization preset id sent with the next upload.
     *
     * Valid values: `"high-quality"`, `"confidential"`, `"alternative"`.
     * Default on Android (returned when unset) is `"confidential"` — the
     * TEE-backed preset the README has always documented as the Android
     * default (v0.8.0 resolved the code/docs contradiction in favor of
     * the privacy-first documented behavior; Tinfoil-router reachability
     * hiccups are retryable per-session from the detail screen).  The
     * user can override and the new choice sticks across launches.
     *
     * Setting to `null` or empty resets to the default on next read.
     */
    var summaryPreset: String?
        get() = prefs.getString(KEY_SUMMARY_PRESET, null) ?: DEFAULT_PRESET
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_SUMMARY_PRESET)
                else putString(KEY_SUMMARY_PRESET, value)
            }.apply()
        }

    /**
     * Whether the next upload should request server-side auto-labeling
     * against the central voiceprint DB.  When false the server skips
     * `meet label --auto` entirely and routes the session straight to
     * the manual labeling page.  Default true preserves existing
     * behavior; the user can opt out via the Record screen Switch and
     * the choice sticks across launches.
     */
    var autoLabel: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LABEL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_LABEL, value).apply()
        }

    /**
     * Whether the next upload should be synced to the server's
     * configured destination git repo after the pipeline completes.
     * When false the session reaches `done (local-only)` on the
     * dashboard with no git push; the user can later trigger a
     * retroactive sync via the dashboard's "Sync now" button.  Default
     * true preserves existing behavior.
     */
    var sync: Boolean
        get() = prefs.getBoolean(KEY_SYNC, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SYNC, value).apply()
        }

    /**
     * Personal recording toggle. When true, the session is hidden from
     * other team members and sync is forced off. Default false (team-visible).
     */
    var personal: Boolean
        get() = prefs.getBoolean(KEY_PERSONAL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PERSONAL, value).apply()
        }

    /**
     * Auto-delete the local recording after a successful upload.
     * Default false (keep recordings on device).
     */
    var autoDeleteAfterUpload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DELETE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DELETE, value).apply()
        }

    /**
     * PEM-encoded CA certificate from a v2 enrollment QR payload.
     *
     * When present, OkHttp trusts this CA alongside the system CAs so
     * the app can reach a vezir server fronted by Caddy's internal CA
     * without a manual cert install on the device. Null for v1
     * enrollments or v2 enrollments that omit `ca_pem`.
     */
    var caPem: String?
        get() = prefs.getString(KEY_CA_PEM, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_CA_PEM) else putString(KEY_CA_PEM, value)
            }.apply()
        }

    // ── Multi-team credentials (v0.3.0+) ──

    /** JSON-encoded list of [TeamCredential] entries. */
    override var teamsJson: String?
        get() = prefs.getString(KEY_TEAMS_JSON, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_TEAMS_JSON)
                else putString(KEY_TEAMS_JSON, value)
            }.apply()
        }

    /** Team slug of the currently active team. */
    override var activeTeamId: String?
        get() = prefs.getString(KEY_ACTIVE_TEAM_ID, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_ACTIVE_TEAM_ID)
                else putString(KEY_ACTIVE_TEAM_ID, value)
            }.apply()
        }

    /** True if the legacy single-token keys are present. */
    fun hasLegacyCredentials(): Boolean =
        !serverUrl.isNullOrBlank() && !token.isNullOrBlank()

    /** True if multi-team credentials are configured. */
    fun hasTeamCredentials(): Boolean =
        !teamsJson.isNullOrBlank()

    /** Clear legacy single-token keys only (after migration). */
    fun clearLegacyCredentials() {
        prefs.edit()
            .remove(KEY_URL)
            .remove(KEY_TOKEN)
            .remove(KEY_CA_PEM)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Convenience: true iff the app has usable credentials —
     * either multi-team or legacy single-token.
     */
    fun isConfigured(): Boolean =
        hasTeamCredentials() || hasLegacyCredentials()

    /**
     * Resolved credential for API calls.  Screens use this instead of
     * reading `serverUrl` / `token` / `caPem` directly so both
     * multi-team and legacy single-token paths are covered.
     */
    data class ActiveCredential(
        val url: String,
        val token: String,
        val caPem: String?,
        val altUrls: List<String> = emptyList(),
        /** Team slug for v0.7.0 X-Team-Id header; null for the legacy
         *  single-token path (pre-multi-team enrollment). */
        val id: String? = null,
    )

    /**
     * Resolve the active credential.
     *
     * Precedence:
     *   1. Multi-team store (active entry in `teamsJson`).
     *   2. Legacy single-token keys (`serverUrl` + `token`).
     *   3. null (not configured).
     */
    fun activeCredential(): ActiveCredential? {
        // 1. Multi-team store
        val store = TeamCredentialStore(this)
        store.getActive()?.let {
            return ActiveCredential(it.url, it.token, it.caPem, it.altUrls, it.id)
        }
        // 2. Legacy fallback: no team_id available.  v0.7.0 servers
        //    will 400 on team-scoped requests until /api/me discovery
        //    runs and populates the multi-team store.
        val url = serverUrl
        val tok = token
        if (!url.isNullOrBlank() && !tok.isNullOrBlank()) {
            return ActiveCredential(url, tok, caPem)
        }
        return null
    }

    companion object {
        @Volatile
        private var instance: Prefs? = null

        /** Process-wide singleton over the encrypted prefs file. */
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }

        const val FILE_NAME = "vezir_secure_prefs"
        private const val KEY_URL = "vezir_url"
        private const val KEY_TOKEN = "vezir_token"
        private const val KEY_CA_PEM = "vezir_ca_pem"
        private const val KEY_PERSONAL = "vezir_personal"
        private const val KEY_AUTO_DELETE = "vezir_auto_delete"
        private const val KEY_SUMMARY_PRESET = "vezir_summary_preset"
        private const val KEY_AUTO_LABEL = "vezir_auto_label"
        private const val KEY_SYNC = "vezir_sync"
        private const val KEY_TEAMS_JSON = "vezir_teams_json"
        private const val KEY_ACTIVE_TEAM_ID = "vezir_active_team_id"
        const val DEFAULT_PRESET = "confidential"

        /**
         * Preset ids and display labels. Shared by RecordScreen (upload
         * dropdown) and SessionDetailScreen (retry-summary picker).
         *
         * Ids must match the server's accepted values (high-quality,
         * confidential, alternative -- see vezir/cli.py and
         * meet/summarize.py SUMMARY_PRESETS).
         */
        val PRESET_OPTIONS: List<Pair<String, String>> = listOf(
            "high-quality" to "High Quality \u2014 Sonnet 4.6",
            "confidential" to "Confidential \u2014 DeepSeek V4 Pro (TEE)",
            "alternative" to "Alternative \u2014 Kimi K2.6",
        )

        fun presetLabelFor(id: String): String =
            PRESET_OPTIONS.firstOrNull { it.first == id }?.second ?: id
    }
}
