package com.vezir.android.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock the Android client's UploadResponse and SessionStatus deserialisers
 * against the exact JSON the Vezir server sends, so a server-side rename
 * doesn't slip past CI.
 */
class ResponseShapesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesUploadResponse() {
        // Real response captured from the live server during M2 smoke
        // (vezir/server/uploads.py:146-151).
        val raw = """
            {
              "session_id": "01KQDPMRKXWTY37YH3KKC2JTF4",
              "bytes": 266242,
              "dashboard_url": "http://muscle.tail178bd.ts.net:8000/s/01KQDPMRKXWTY37YH3KKC2JTF4",
              "dashboard_login_url": "http://muscle.tail178bd.ts.net:8000/login?token=vzr_X&next=%2Fs%2F01KQDPMRKXWTY37YH3KKC2JTF4"
            }
        """.trimIndent()
        val r = json.decodeFromString(Uploader.UploadResponse.serializer(), raw)
        assertEquals("01KQDPMRKXWTY37YH3KKC2JTF4", r.session_id)
        assertEquals(266_242L, r.bytes)
        assertTrue(r.dashboard_url.endsWith("/s/01KQDPMRKXWTY37YH3KKC2JTF4"))
        assertTrue(r.dashboard_login_url.contains("token="))
    }

    @Test
    fun parsesSessionStatus_terminalAndNonTerminal() {
        val queued = """
            {
              "id": "01KQ...",
              "github": "kasita",
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

        for (terminal in listOf("done", "error")) {
            val raw = """{"id":"x","status":"$terminal"}"""
            val s = json.decodeFromString(SessionPoller.SessionStatus.serializer(), raw)
            assertEquals(terminal, s.status)
            assertTrue(s.isTerminal)
        }
    }

    @Test
    fun ignoresUnknownFields() {
        // Server may add fields in the future; we must not break.
        val raw = """{"id":"x","status":"done","new_field":42,"another":["a","b"]}"""
        val s = json.decodeFromString(SessionPoller.SessionStatus.serializer(), raw)
        assertEquals("done", s.status)
    }
}
