package com.vezir.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure (Intent-free) NIP-55 result parsers.  The
 * Intent-extraction wrappers (parseLogin/parseSign) just read extras and
 * delegate here, so this covers the real logic without Robolectric.
 */
class AmberSignerTest {

    private val pubkey = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val unsigned = Nip98Event.buildLogin(
        pubkeyHex = pubkey,
        loginUrl = "https://vezir.twentyone.ist/api/auth/nostr/login",
        createdAt = 1700000000L,
    )
    private val sig128 = "a".repeat(128)

    // ── login ────────────────────────────────────────────────────────────

    @Test
    fun login_hexPubkey_success() {
        val r = AmberSigner.parseLoginExtras(pubkey, "com.greenart7c3.nostrsigner", false)
        assertTrue(r is AmberSigner.LoginResult.Success)
        r as AmberSigner.LoginResult.Success
        assertEquals(pubkey, r.pubkeyHex)
        assertEquals("com.greenart7c3.nostrsigner", r.signerPackage)
    }

    @Test
    fun login_rejected() {
        assertTrue(AmberSigner.parseLoginExtras(null, null, true) is AmberSigner.LoginResult.Rejected)
    }

    @Test
    fun login_emptyResult_failed() {
        assertTrue(AmberSigner.parseLoginExtras("", null, false) is AmberSigner.LoginResult.Failed)
        assertTrue(AmberSigner.parseLoginExtras(null, null, false) is AmberSigner.LoginResult.Failed)
    }

    @Test
    fun login_badHex_failed() {
        assertTrue(AmberSigner.parseLoginExtras("not-hex", null, false) is AmberSigner.LoginResult.Failed)
        // wrong length
        assertTrue(AmberSigner.parseLoginExtras("abcd", null, false) is AmberSigner.LoginResult.Failed)
    }

    // ── sign ─────────────────────────────────────────────────────────────

    @Test
    fun sign_fullEvent_success() {
        val signedJson = unsigned.toJson(sig = sig128)
        val r = AmberSigner.parseSignExtras(event = signedJson, result = null, rejected = false, unsigned = unsigned)
        assertTrue(r is AmberSigner.SignResult.Success)
        r as AmberSigner.SignResult.Success
        assertTrue(r.signedEventJson.contains(sig128))
    }

    @Test
    fun sign_signatureOnly_stitched() {
        val r = AmberSigner.parseSignExtras(event = null, result = sig128, rejected = false, unsigned = unsigned)
        assertTrue(r is AmberSigner.SignResult.Success)
        r as AmberSigner.SignResult.Success
        // The stitched event must carry our id + the returned sig.
        assertTrue(r.signedEventJson.contains(unsigned.id))
        assertTrue(r.signedEventJson.contains(sig128))
    }

    @Test
    fun sign_rejected() {
        val r = AmberSigner.parseSignExtras(event = null, result = null, rejected = true, unsigned = unsigned)
        assertTrue(r is AmberSigner.SignResult.Rejected)
    }

    @Test
    fun sign_eventMissingSig_failed() {
        val noSig = unsigned.toJson() // no sig
        val r = AmberSigner.parseSignExtras(event = noSig, result = null, rejected = false, unsigned = unsigned)
        assertTrue(r is AmberSigner.SignResult.Failed)
    }

    @Test
    fun sign_shortSignature_failed() {
        val r = AmberSigner.parseSignExtras(event = null, result = "abcd", rejected = false, unsigned = unsigned)
        assertTrue(r is AmberSigner.SignResult.Failed)
    }

    @Test
    fun sign_nothing_failed() {
        val r = AmberSigner.parseSignExtras(event = null, result = null, rejected = false, unsigned = unsigned)
        assertTrue(r is AmberSigner.SignResult.Failed)
    }
}
