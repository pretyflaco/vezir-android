package com.vezir.android.auth

import java.util.Base64

/**
 * Client-side inspection of a vezir session JWT's expiry.
 *
 * The session JWT (vezir/server/nostr_auth.py) is HS256-signed with a
 * server-side secret, so we cannot *verify* it on-device — but the `exp`
 * claim (epoch seconds, 24h TTL) is plain base64url in the payload
 * segment and is readable without the secret.  We use it only to warn the
 * user before an upload that is doomed to 401, never as an authorization
 * decision (the server remains the sole authority).
 *
 * Opaque `vzr_…` machine/CI tokens are not JWTs (no `header.payload.sig`
 * structure); for those every method here is a safe no-op — they carry a
 * 90-day server-managed lifetime that the client can't introspect.
 *
 * Implementation note: uses `java.util.Base64` (API 26+, fine for our
 * minSdk 29) and a regex `exp` extraction rather than `android.util.Base64`
 * / `org.json`, so the logic is plain-JVM unit-testable without Robolectric.
 */
object SessionExpiry {

    // Matches `"exp":1700000000` (optionally spaced) in the decoded payload.
    private val EXP_REGEX = Regex("\"exp\"\\s*:\\s*(\\d+)")

    /**
     * Extract the `exp` claim (epoch seconds) from a JWT, or null if the
     * token isn't a JWT / has no parseable `exp`.  Never throws.
     */
    fun expiresAtEpoch(token: String): Long? {
        val parts = token.split(".")
        if (parts.size != 3) return null // not a JWT (e.g. vzr_ opaque token)
        return try {
            val payload = Base64.getUrlDecoder().decode(padBase64(parts[1]))
            val json = String(payload, Charsets.UTF_8)
            EXP_REGEX.find(json)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0L }
        } catch (_: Throwable) {
            null
        }
    }

    /** JWT payloads are base64url **without** padding; restore it for the decoder. */
    private fun padBase64(s: String): String =
        when (s.length % 4) {
            2 -> "$s=="
            3 -> "$s="
            else -> s
        }

    /**
     * True iff [token] is a JWT that is already expired or will expire
     * within [marginSec] seconds (default 5 min, to avoid starting an
     * upload that would 401 mid-transfer).
     *
     * Returns false for non-JWT tokens and for JWTs whose `exp` we can't
     * read — i.e. we only block when we're *sure* the session is stale,
     * so a parsing quirk never wrongly stops a valid upload.
     */
    fun isExpired(token: String, marginSec: Long = 300): Boolean {
        val exp = expiresAtEpoch(token) ?: return false
        val nowSec = System.currentTimeMillis() / 1000L
        return nowSec + marginSec >= exp
    }
}
