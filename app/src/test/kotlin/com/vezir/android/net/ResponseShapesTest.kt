package com.vezir.android.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock the Android client's UploadResponse, SessionStatus and MeApi
 * deserialisers against the JSON the Vezir server sends, so a
 * server-side rename doesn't slip past CI.
 *
 * v0.5.0 (vezir 0.7.0):
 *   * UploadResponse: dashboard_url + dashboard_login_url dropped.
 *   * MeApi.MeResponse: team_id + team_name replaced with memberships[].
 */
class ResponseShapesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesUploadResponse_v070() {
        // vezir 0.7.0 response shape: no dashboard URLs.
        val raw = """
            {
              "session_id": "01J3C3RGCSPJ5ES8N1A6PY2MQ4",
              "bytes": 266242
            }
        """.trimIndent()
        val r = json.decodeFromString(Uploader.UploadResponse.serializer(), raw)
        assertEquals("01J3C3RGCSPJ5ES8N1A6PY2MQ4", r.session_id)
        assertEquals(266_242L, r.bytes)
    }

    @Test
    fun parsesUploadResponse_legacy_06x_ignoresExtraKeys() {
        // Old vezir 0.6.x responses still carried dashboard_* fields.
        // We accept them but don't decode them (ignoreUnknownKeys).
        val raw = """
            {
              "session_id": "01J3C3RGCSPJ5ES8N1A6PY2MQ4",
              "bytes": 266242,
              "dashboard_url": "http://srv/s/01J3C3RGCSPJ5ES8N1A6PY2MQ4",
              "dashboard_login_url": "http://srv/login?code=vzx_x&next=%2Fs%2F01J3C3RGCSPJ5ES8N1A6PY2MQ4"
            }
        """.trimIndent()
        val r = json.decodeFromString(Uploader.UploadResponse.serializer(), raw)
        assertEquals("01J3C3RGCSPJ5ES8N1A6PY2MQ4", r.session_id)
        assertEquals(266_242L, r.bytes)
    }

    @Test
    fun parsesSessionStatus_terminalAndNonTerminal() {
        val queued = """
            {
              "id": "01KQ...",
              "github": "ivan",
              "title": "smoke",
              "status": "queued",
              "created_at": "2026-04-30T01:00:00Z",
              "updated_at": "2026-04-30T01:00:00Z",
              "error": null,
              "artifacts": null,
              "artifacts_dict": {}
            }
        """.trimIndent()
        val s1 = json.decodeFromString(SessionPoller.SessionStatus.serializer(), queued)
        assertEquals("queued", s1.status)
        assertTrue(!s1.isTerminal)
        assertNull(s1.error)

        // 'empty' (vezir >= 0.11.1) is a terminal state: a recording with
        // no audio that is never synced.
        for (terminal in listOf("done", "error", "empty")) {
            val raw = """{"id":"x","status":"$terminal"}"""
            val s = json.decodeFromString(SessionPoller.SessionStatus.serializer(), raw)
            assertEquals(terminal, s.status)
            assertTrue(s.isTerminal)
        }
    }

    @Test
    fun parsesUploadResponse_multi_withParts() {
        // vezir >= 0.9.0 /upload/multi response carries a `parts` count.
        val raw = """
            {
              "session_id": "01J3C3RGCSPJ5ES8N1A6PY2MQ4",
              "bytes": 532484,
              "parts": 3
            }
        """.trimIndent()
        val r = json.decodeFromString(Uploader.UploadResponse.serializer(), raw)
        assertEquals("01J3C3RGCSPJ5ES8N1A6PY2MQ4", r.session_id)
        assertEquals(532_484L, r.bytes)
        assertEquals(3, r.parts)
    }

    @Test
    fun uploadResponse_singleFile_hasNullParts() {
        // The one-shot /upload response has no `parts` field.
        val raw = """{"session_id":"x","bytes":1}"""
        val r = json.decodeFromString(Uploader.UploadResponse.serializer(), raw)
        assertNull(r.parts)
    }

    @Test
    fun ignoresUnknownFields() {
        // Server may add fields in the future; we must not break.
        val raw = """{"id":"x","status":"done","new_field":42,"another":["a","b"]}"""
        val s = json.decodeFromString(SessionPoller.SessionStatus.serializer(), raw)
        assertEquals("done", s.status)
    }

    @Test
    fun parsesMeResponse_v070_withMemberships() {
        // vezir 0.7.0 /api/me shape.
        val raw = """
            {
              "github": "judy",
              "is_admin": true,
              "memberships": [
                {"team_id": "team-a",     "team_name": "Team A",     "role": "admin"},
                {"team_id": "team-b", "team_name": "Team B", "role": "scribe"}
              ],
              "alternate_urls": ["http://devbox.example.ts.net:8000"]
            }
        """.trimIndent()
        val me = json.decodeFromString(MeApi.MeResponse.serializer(), raw)
        assertEquals("judy", me.github)
        assertTrue(me.is_admin)
        assertEquals(2, me.memberships.size)
        assertEquals("team-a", me.memberships[0].team_id)
        assertEquals("Team A", me.memberships[0].team_name)
        assertEquals("admin", me.memberships[0].role)
        assertEquals("team-b", me.memberships[1].team_id)
        assertEquals("scribe", me.memberships[1].role)
        assertEquals(1, me.alternate_urls.size)
    }

    @Test
    fun parsesMeResponse_emptyMemberships() {
        // Token whose handle has no memberships -- still a 200 response.
        val raw = """
            {
              "github": "orphan",
              "is_admin": false,
              "memberships": []
            }
        """.trimIndent()
        val me = json.decodeFromString(MeApi.MeResponse.serializer(), raw)
        assertEquals("orphan", me.github)
        assertTrue(me.memberships.isEmpty())
        assertTrue(me.alternate_urls.isEmpty())
    }
}
