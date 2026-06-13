package com.vezir.android.auth

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Builds the unsigned NIP-98 (kind 27235) HTTP-auth event that the vezir
 * server verifies at `POST /api/auth/nostr/login`, and computes its event
 * id locally so Amber only has to Schnorr-sign.
 *
 * The server (vezir `nip98.py`) recomputes the id as
 * `sha256(canonical_json)` where canonical_json is
 * `[0, pubkey, created_at, kind, tags, content]` serialised exactly like
 * JS `JSON.stringify` — no insignificant whitespace, keys/array order
 * preserved, non-ASCII verbatim.  We must match that byte-for-byte or the
 * recomputed id won't match and verification fails.
 *
 * It also binds the proof to the request: a `["u", <login-url>]` tag and a
 * `["method", "POST"]` tag, plus a fresh `created_at` (server window is
 * 120 s, ±60 s future tolerance).
 *
 * Amber (NIP-55) does the actual signing; this class never touches a
 * private key.
 */
object Nip98Event {

    const val KIND = 27235

    /**
     * An unsigned event ready to hand to Amber.  [id] is precomputed; Amber
     * fills in `sig` (and may echo the whole event back).  [toJson] yields
     * the JSON string appended to the `nostrsigner:` URI.
     */
    data class Unsigned(
        val id: String,
        val pubkey: String,
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
    ) {
        /** Full event JSON (with empty sig) for the `nostrsigner:` URI. */
        fun toJson(sig: String = ""): String {
            val obj = JSONObject()
            // Field order here is cosmetic for Amber's parse; the id was
            // computed from the canonical array form below.
            obj.put("id", id)
            obj.put("pubkey", pubkey)
            obj.put("created_at", createdAt)
            obj.put("kind", kind)
            obj.put("tags", tagsToJson(tags))
            obj.put("content", content)
            if (sig.isNotEmpty()) obj.put("sig", sig)
            return obj.toString()
        }
    }

    /**
     * Build the unsigned login event for [loginUrl] (the public
     * `…/api/auth/nostr/login` URL) signed-for-method [method] (POST),
     * authored by [pubkeyHex].
     */
    fun buildLogin(
        pubkeyHex: String,
        loginUrl: String,
        method: String = "POST",
        createdAt: Long = System.currentTimeMillis() / 1000L,
    ): Unsigned {
        val tags = listOf(
            listOf("u", loginUrl),
            listOf("method", method),
        )
        val content = ""
        val id = computeId(pubkeyHex, createdAt, KIND, tags, content)
        return Unsigned(id, pubkeyHex, createdAt, KIND, tags, content)
    }

    /**
     * Recompute the event id the same way the server does:
     * `sha256(hex)` over the canonical JSON of
     * `[0, pubkey, created_at, kind, tags, content]`.
     */
    fun computeId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String {
        val canonical = canonicalJson(pubkey, createdAt, kind, tags, content)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    /**
     * Canonical serialisation matching JS `JSON.stringify([0, pubkey,
     * created_at, kind, tags, content])` and Python
     * `json.dumps(..., separators=(",", ":"), ensure_ascii=False)`.
     *
     * We build it by hand (not via org.json's toString, which is close but
     * we want guaranteed control over string escaping and no spaces) so the
     * bytes are identical to the server's.
     */
    fun canonicalJson(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String {
        val sb = StringBuilder()
        sb.append('[')
        sb.append('0').append(',')
        sb.append(jsonString(pubkey)).append(',')
        sb.append(createdAt).append(',')
        sb.append(kind).append(',')
        // tags: array of arrays of strings
        sb.append('[')
        tags.forEachIndexed { i, tag ->
            if (i > 0) sb.append(',')
            sb.append('[')
            tag.forEachIndexed { j, v ->
                if (j > 0) sb.append(',')
                sb.append(jsonString(v))
            }
            sb.append(']')
        }
        sb.append(']').append(',')
        sb.append(jsonString(content))
        sb.append(']')
        return sb.toString()
    }

    private fun tagsToJson(tags: List<List<String>>): JSONArray {
        val outer = JSONArray()
        for (tag in tags) {
            val inner = JSONArray()
            for (v in tag) inner.put(v)
            outer.put(inner)
        }
        return outer
    }

    /**
     * JSON string escaping matching JS `JSON.stringify` / Python
     * `json.dumps(ensure_ascii=False)`: escape `"` `\` and the control
     * characters; emit everything else (incl. non-ASCII) verbatim.
     */
    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (ch < '\u0020') {
                        sb.append("\\u")
                        sb.append("%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun ByteArray.toHex(): String {
        val hex = CharArray(size * 2)
        val digits = "0123456789abcdef"
        for (i in indices) {
            val b = this[i].toInt() and 0xFF
            hex[i * 2] = digits[b ushr 4]
            hex[i * 2 + 1] = digits[b and 0x0F]
        }
        return String(hex)
    }
}
