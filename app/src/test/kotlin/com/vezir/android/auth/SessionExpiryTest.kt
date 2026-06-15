package com.vezir.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.Base64
import org.junit.Test

/**
 * Unit tests for [SessionExpiry]. Plain JVM (no Android stubs): the class
 * deliberately uses java.util.Base64 + regex so it runs without Robolectric.
 */
class SessionExpiryTest {

    /** Build a JWT-shaped string `header.payload.sig` with the given exp. */
    private fun jwtWithExp(exp: Long): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(
            """{"iss":"vezir","sub":"pretyflaco","is_admin":false,"iat":1,"exp":$exp}"""
                .toByteArray(),
        )
        // Signature segment content is irrelevant to exp parsing.
        return "$header.$payload.c2lnbmF0dXJl"
    }

    @Test
    fun expiresAtEpoch_readsExpFromJwt() {
        assertEquals(1_700_000_000L, SessionExpiry.expiresAtEpoch(jwtWithExp(1_700_000_000L)))
    }

    @Test
    fun expiresAtEpoch_nullForOpaqueVzrToken() {
        // vzr_ tokens are not 3-segment JWTs.
        assertNull(SessionExpiry.expiresAtEpoch("vzr_abcdef0123456789"))
    }

    @Test
    fun expiresAtEpoch_nullForGarbage() {
        assertNull(SessionExpiry.expiresAtEpoch("not.a.jwt"))
        assertNull(SessionExpiry.expiresAtEpoch(""))
    }

    @Test
    fun isExpired_trueForPastToken() {
        val past = System.currentTimeMillis() / 1000L - 60L
        assertTrue(SessionExpiry.isExpired(jwtWithExp(past)))
    }

    @Test
    fun isExpired_trueWithinMargin() {
        // exp is 2 min out, default margin is 5 min → treated as expired.
        val soon = System.currentTimeMillis() / 1000L + 120L
        assertTrue(SessionExpiry.isExpired(jwtWithExp(soon)))
    }

    @Test
    fun isExpired_falseForFreshToken() {
        val future = System.currentTimeMillis() / 1000L + 24L * 3600L
        assertFalse(SessionExpiry.isExpired(jwtWithExp(future)))
    }

    @Test
    fun isExpired_falseForOpaqueToken() {
        // Non-JWT tokens can't be introspected; never block on them.
        assertFalse(SessionExpiry.isExpired("vzr_abcdef0123456789"))
    }
}
