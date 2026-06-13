package com.vezir.android.auth

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON-contract tests for the Google device-flow response shapes.  The
 * polling/network behaviour needs an Android/OkHttp context; here we just
 * pin that the (de)serialisation matches the vezir server's responses.
 */
class GoogleLoginApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun config_notConfigured() {
        val c = json.decodeFromString(
            GoogleLoginApi.Config.serializer(),
            """{"configured":false,"client_id":null,"allowed_domain":null}""",
        )
        assertFalse(c.configured)
    }

    @Test
    fun config_configured() {
        val c = json.decodeFromString(
            GoogleLoginApi.Config.serializer(),
            """{"configured":true,"client_id":"x.apps.googleusercontent.com","allowed_domain":"blinkbtc.com"}""",
        )
        assertTrue(c.configured)
        assertEquals("blinkbtc.com", c.allowed_domain)
    }

    @Test
    fun deviceStart_parsesCodeAndInterval() {
        val d = json.decodeFromString(
            GoogleLoginApi.DeviceStart.serializer(),
            """{"device_code":"dc","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5,"allowed_domain":"blinkbtc.com"}""",
        )
        assertEquals("ABCD-EFGH", d.user_code)
        assertEquals("dc", d.device_code)
        assertEquals(5, d.interval)
        assertEquals("https://www.google.com/device", d.verification_url)
    }

    @Test
    fun loginResponse_parsesJwtAndEmail() {
        val r = json.decodeFromString(
            GoogleLoginApi.LoginResponse.serializer(),
            """{"session_jwt":"jwt123","github":"pretyflaco","is_admin":true,"email":"kemal@blinkbtc.com","expires_in":86400}""",
        )
        assertEquals("jwt123", r.session_jwt)
        assertEquals("pretyflaco", r.github)
        assertTrue(r.is_admin)
        assertEquals("kemal@blinkbtc.com", r.email)
    }
}
