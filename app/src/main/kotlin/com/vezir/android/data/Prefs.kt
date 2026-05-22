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
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
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
     * Default on Android (returned when unset) is `"confidential"` so a
     * fresh install opts into the TEE-backed pipeline by default; the user
     * can override and the new choice sticks across launches.
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

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Convenience: true iff both URL and token are set. */
    fun isConfigured(): Boolean =
        !serverUrl.isNullOrBlank() && !token.isNullOrBlank()

    companion object {
        const val FILE_NAME = "vezir_secure_prefs"
        private const val KEY_URL = "vezir_url"
        private const val KEY_TOKEN = "vezir_token"
        private const val KEY_CA_PEM = "vezir_ca_pem"
        private const val KEY_PERSONAL = "vezir_personal"
        private const val KEY_AUTO_DELETE = "vezir_auto_delete"
        private const val KEY_SUMMARY_PRESET = "vezir_summary_preset"
        private const val KEY_AUTO_LABEL = "vezir_auto_label"
        private const val KEY_SYNC = "vezir_sync"
        const val DEFAULT_PRESET = "confidential"
    }
}
