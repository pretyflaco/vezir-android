package com.vezir.android.auth

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verify the nostr + Google login responses parse the new rotating-session
 * fields (`access_jwt`, `refresh_token`, `refresh_expires_in`) from a
 * vezir >= 0.10.0 server, and still deserialise a pre-0.10.0 response that
 * omits them (backward compatibility).
 */
class LoginResponseRefreshTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun nostrLogin_parsesRefreshFields() {
        val raw = """
            {
              "session_jwt": "eyJ.a.b",
              "access_jwt": "eyJ.a.b",
              "refresh_token": "vzrt_ABC",
              "expires_in": 3600,
              "refresh_expires_in": 604800,
              "github": "alice",
              "is_admin": false,
              "npub": "ab",
              "memberships": []
            }
        """.trimIndent()
        val r = json.decodeFromString(NostrLoginApi.LoginResponse.serializer(), raw)
        assertEquals("vzrt_ABC", r.refresh_token)
        assertEquals("eyJ.a.b", r.access_jwt)
        assertEquals(3600L, r.expires_in)
        assertEquals(604800L, r.refresh_expires_in)
    }

    @Test
    fun nostrLogin_preRefreshServer_stillParses() {
        // vezir < 0.10.0: no access_jwt / refresh_token at all.
        val raw = """{"session_jwt":"eyJ.old.jwt","github":"alice","expires_in":86400}"""
        val r = json.decodeFromString(NostrLoginApi.LoginResponse.serializer(), raw)
        assertEquals("eyJ.old.jwt", r.session_jwt)
        assertEquals("", r.refresh_token)
        assertEquals("", r.access_jwt)
    }

    @Test
    fun googleLogin_parsesRefreshFields() {
        val raw = """
            {
              "session_jwt": "eyJ.g.b",
              "access_jwt": "eyJ.g.b",
              "refresh_token": "vzrt_GOOG",
              "expires_in": 3600,
              "refresh_expires_in": 604800,
              "github": "bob",
              "email": "bob@blinkbtc.com"
            }
        """.trimIndent()
        val r = json.decodeFromString(GoogleLoginApi.LoginResponse.serializer(), raw)
        assertEquals("vzrt_GOOG", r.refresh_token)
        assertEquals("bob@blinkbtc.com", r.email)
    }
}
