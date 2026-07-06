package com.vezir.android.net

import android.content.Context

/**
 * Persists in-flight resumable-upload state so an upload survives process
 * death (v0.8.0).
 *
 * Before this, the tus `upload_id` lived only in a local variable: if the
 * OS killed the app mid-transfer, the next attempt created a brand-new
 * server session and re-sent from byte 0.  Now the id is written here as
 * soon as the server session exists; [UploadWorker] reads it back on a
 * retry (including a WorkManager reschedule after process death) and
 * resumes from the server's offset.
 *
 * Plain (non-encrypted) SharedPreferences on purpose: the values are a
 * ULID upload id, the base URL, and a content URI — none secret, and the
 * server enforces uploader/team ownership on the id.
 */
class UploadStateStore(context: Context) {

    private val prefs =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    data class State(
        val uploadId: String,
        val baseUrl: String,
    )

    /** Persist the upload session for [contentUri]. */
    fun put(contentUri: String, uploadId: String, baseUrl: String) {
        prefs.edit()
            .putString(KEY_URI, contentUri)
            .putString(KEY_UPLOAD_ID, uploadId)
            .putString(KEY_BASE_URL, baseUrl)
            .apply()
    }

    /**
     * Return the persisted state for [contentUri], or null when none is
     * stored or it belongs to a different file/server (stale).
     */
    fun get(contentUri: String, baseUrl: String): State? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        val id = prefs.getString(KEY_UPLOAD_ID, null) ?: return null
        val url = prefs.getString(KEY_BASE_URL, null) ?: return null
        if (uri != contentUri || url != baseUrl) return null
        return State(uploadId = id, baseUrl = url)
    }

    /** Drop the persisted state (upload finished or permanently failed). */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREF_FILE = "vezir_upload_state"
        private const val KEY_URI = "content_uri"
        private const val KEY_UPLOAD_ID = "upload_id"
        private const val KEY_BASE_URL = "base_url"
    }
}
