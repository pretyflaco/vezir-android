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
    }
}
