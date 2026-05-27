package com.vezir.android.net

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the [HttpClients.authHeaders] helper that adds
 * Authorization + X-Team-Id headers on every API request.
 *
 * v0.5.0 (vezir 0.7.0): X-Team-Id is required on every team-scoped
 * endpoint; missing -> 400, non-member -> 403.  This helper is the
 * single source of truth for header construction across the 7 API
 * classes in [com.vezir.android.net].
 */
class HttpClientsTest {

    @Test
    fun addsBothHeaders_whenTeamIdProvided() {
        val req = HttpClients.authHeaders(
            Request.Builder().url("https://srv.example/api/sessions"),
            token = "vzr_test",
            teamId = "blink",
        ).get().build()
        assertEquals("Bearer vzr_test", req.header("Authorization"))
        assertEquals("blink", req.header("X-Team-Id"))
    }

    @Test
    fun omitsTeamHeader_whenTeamIdNull() {
        // /api/me + /health don't need (and don't accept) X-Team-Id.
        val req = HttpClients.authHeaders(
            Request.Builder().url("https://srv.example/api/me"),
            token = "vzr_test",
            teamId = null,
        ).get().build()
        assertEquals("Bearer vzr_test", req.header("Authorization"))
        assertNull(req.header("X-Team-Id"))
    }

    @Test
    fun omitsTeamHeader_whenTeamIdBlank() {
        // Defensive: empty string treated the same as null.  A v0.7.0
        // server would 400 on an empty X-Team-Id; we don't even send it.
        val req = HttpClients.authHeaders(
            Request.Builder().url("https://srv.example/api/me"),
            token = "vzr_test",
            teamId = "",
        ).get().build()
        assertNull(req.header("X-Team-Id"))
    }
}
