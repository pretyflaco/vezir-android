package com.vezir.android.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal NIP-55 (Android signer) client for the Amber app.
 *
 * Unlike the desktop CLI (which uses NIP-46 over relays), Android talks to
 * the local signer via `nostrsigner:` intents — no relays, no clock-skew.
 * vezir only needs two operations:
 *
 *   1. `get_public_key` (login) → the user's hex pubkey, and which signer
 *      package answered.
 *   2. `sign_event` → Amber Schnorr-signs our unsigned NIP-98 event.
 *
 * This object only *builds* the intents and *parses* the result intents
 * (pure, unit-testable).  The Compose layer owns the
 * `rememberLauncherForActivityResult(StartActivityForResult)` plumbing.
 *
 * Wire contract (matches Amber / Amethyst quartz `nip55AndroidSigner`):
 *   - login intent:  ACTION_VIEW, data `nostrsigner:`, extras
 *                    `type=get_public_key`, `permissions=<json>`.
 *   - sign intent:   ACTION_VIEW, data `nostrsigner:<unsigned-event-json>`,
 *                    `package=<signer>`, extras `type=sign_event`,
 *                    `current_user=<hexpubkey>`.
 *   - result extras: `result` (pubkey on login, or 128-hex sig on sign),
 *                    `event` (full signed event JSON on sign),
 *                    `package` (signer pkg, login), `rejected` (bool).
 */
object AmberSigner {

    private const val SCHEME = "nostrsigner:"

    /** True if any NIP-55 signer (Amber) can handle `nostrsigner:`. */
    fun isSignerInstalled(context: Context): Boolean =
        context.packageManager.queryIntentActivities(probeIntent(), 0).isNotEmpty()

    /** Package name of the first installed signer, or null. */
    fun installedSignerPackage(context: Context): String? =
        context.packageManager
            .queryIntentActivities(probeIntent(), 0)
            .firstOrNull()
            ?.activityInfo
            ?.packageName

    private fun probeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME))

    // ── login (get_public_key) ────────────────────────────────────────────

    /**
     * Intent that asks the signer for the user's public key, requesting the
     * sign_event(kind 27235) permission up front so the user approves once.
     * [signerPackage] (when non-null) targets a specific signer.
     */
    fun loginIntent(signerPackage: String? = null): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME))
        intent.putExtra("type", "get_public_key")
        intent.putExtra("permissions", defaultPermissionsJson())
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!signerPackage.isNullOrBlank()) intent.`package` = signerPackage
        return intent
    }

    /** `[{"type":"sign_event","kind":27235}]` — only what vezir needs. */
    fun defaultPermissionsJson(): String {
        val arr = JSONArray()
        val perm = JSONObject()
        perm.put("type", "sign_event")
        perm.put("kind", Nip98Event.KIND)
        arr.put(perm)
        return arr.toString()
    }

    /** Parsed login result. */
    sealed class LoginResult {
        data class Success(val pubkeyHex: String, val signerPackage: String?) : LoginResult()
        object Rejected : LoginResult()
        data class Failed(val reason: String) : LoginResult()
    }

    /** Parse the Intent returned by the login launcher. */
    fun parseLogin(data: Intent?): LoginResult {
        if (data == null) return LoginResult.Failed("no result data")
        return parseLoginExtras(
            result = data.getStringExtra("result"),
            signerPackage = data.getStringExtra("package"),
            rejected = data.hasExtra("rejected"),
        )
    }

    /**
     * Pure login-result logic (no Intent), so it's unit-testable.
     * [result] is the signer's `result` extra (hex or npub pubkey).
     */
    fun parseLoginExtras(result: String?, signerPackage: String?, rejected: Boolean): LoginResult {
        if (rejected) return LoginResult.Rejected
        val pubkey = result?.trim()
        if (pubkey.isNullOrEmpty()) {
            return LoginResult.Failed("signer returned no public key")
        }
        // Amber returns hex (64) or sometimes npub-bech32; vezir needs hex.
        val hex = if (pubkey.startsWith("npub1")) {
            Bech32.npubToHex(pubkey) ?: return LoginResult.Failed("bad npub")
        } else {
            pubkey.lowercase()
        }
        if (!isHex64(hex)) return LoginResult.Failed("pubkey not 64-hex")
        return LoginResult.Success(hex, signerPackage)
    }

    // ── sign_event ─────────────────────────────────────────────────────────

    /**
     * Intent asking the signer to sign [unsigned].  [loggedInUser] is the
     * hex pubkey (Amber's `current_user`); [signerPackage] targets the
     * signer that answered login.
     */
    fun signIntent(
        unsigned: Nip98Event.Unsigned,
        loggedInUser: String,
        signerPackage: String?,
    ): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME + Uri.encode(unsigned.toJson())))
        intent.putExtra("type", "sign_event")
        intent.putExtra("current_user", loggedInUser)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (!signerPackage.isNullOrBlank()) intent.`package` = signerPackage
        return intent
    }

    /** Parsed sign result: the full signed event JSON ready to base64. */
    sealed class SignResult {
        data class Success(val signedEventJson: String) : SignResult()
        object Rejected : SignResult()
        data class Failed(val reason: String) : SignResult()
    }

    /**
     * Parse the Intent returned by the sign launcher.  The signer may
     * return either the full signed event (`event` extra) or just the
     * 128-hex signature (`result`), in which case we stitch it onto the
     * unsigned event.
     */
    fun parseSign(data: Intent?, unsigned: Nip98Event.Unsigned): SignResult {
        if (data == null) return SignResult.Failed("no result data")
        return parseSignExtras(
            event = data.getStringExtra("event"),
            result = data.getStringExtra("result"),
            rejected = data.hasExtra("rejected"),
            unsigned = unsigned,
        )
    }

    /**
     * Pure sign-result logic (no Intent), so it's unit-testable.  The signer
     * may return the full signed [event] JSON or just the 128-hex [result]
     * signature (which we stitch onto [unsigned]).
     */
    fun parseSignExtras(
        event: String?,
        result: String?,
        rejected: Boolean,
        unsigned: Nip98Event.Unsigned,
    ): SignResult {
        if (rejected) return SignResult.Rejected

        val ev = event?.trim()
        if (!ev.isNullOrEmpty() && ev.startsWith("{")) {
            return try {
                val obj = JSONObject(ev)
                val sig = obj.optString("sig", "")
                if (sig.length != 128) {
                    SignResult.Failed("signed event missing 128-hex sig")
                } else {
                    SignResult.Success(ev)
                }
            } catch (e: Exception) {
                SignResult.Failed("unparseable signed event: ${e.message}")
            }
        }

        val sig = result?.trim()
        if (!sig.isNullOrEmpty() && sig.length == 128 && isHex(sig)) {
            return SignResult.Success(unsigned.toJson(sig))
        }
        return SignResult.Failed("signer returned no signature")
    }

    private fun isHex64(s: String): Boolean = s.length == 64 && isHex(s)

    private fun isHex(s: String): Boolean = s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
