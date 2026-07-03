package com.vezir.android.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lock the `POST /api/auth/refresh` response deserialiser against the JSON
 * the vezir server (>= 0.10.0) sends, and verify the access-token fallback.
 */
class RefreshApiShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesRefreshResponse() {
        val raw = """
            {
              "session_jwt": "eyJ.a.b",
              "access_jwt": "eyJ.a.b",
              "refresh_token": "vzrt_NEW",
              "expires_in": 3600,
              "refresh_expires_in": 604800,
              "session_max_ttl": 2592000,
              "sid": "abc123"
            }
        """.trimIndent()
        val r = json.decodeFromString(RefreshApi.RefreshResponse.serializer(), raw)
        assertEquals("eyJ.a.b", r.accessToken())
        assertEquals("vzrt_NEW", r.refresh_token)
        assertEquals(3600L, r.expires_in)
        assertEquals(604800L, r.refresh_expires_in)
    }

    @Test
    fun accessTokenFallsBackToSessionJwt() {
        // A server that only sends session_jwt (no access_jwt) still works.
        val raw = """{"session_jwt":"eyJ.only.session","refresh_token":"vzrt_X","expires_in":3600}"""
        val r = json.decodeFromString(RefreshApi.RefreshResponse.serializer(), raw)
        assertEquals("eyJ.only.session", r.accessToken())
    }

    @Test
    fun ignoresUnknownFields() {
        val raw = """{"access_jwt":"a.b.c","refresh_token":"vzrt_Y","future_field":true}"""
        val r = json.decodeFromString(RefreshApi.RefreshResponse.serializer(), raw)
        assertEquals("a.b.c", r.accessToken())
        assertEquals("vzrt_Y", r.refresh_token)
    }
}
