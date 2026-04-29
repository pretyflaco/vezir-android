package com.vezir.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the parser stays in lockstep with the server-side payload
 * defined in `vezir/server/enroll.py:build_payload`.
 */
class EnrollmentPayloadTest {

    @Test
    fun parsesCanonicalServerPayload() {
        // Exactly what segno encodes from build_payload(...) on the server.
        val raw = """{"token":"vzr_abc","url":"http://muscle.tail178bd.ts.net:8000","v":1}"""
        val p = EnrollmentPayload.parse(raw)
        assertNotNull(p)
        assertEquals(1, p!!.v)
        assertEquals("http://muscle.tail178bd.ts.net:8000", p.url)
        assertEquals("vzr_abc", p.token)
    }

    @Test
    fun toleratesWhitespace() {
        val raw = "\n  {\"v\":1,\"url\":\"https://example.com\",\"token\":\"vzr_x\"}  \n"
        val p = EnrollmentPayload.parse(raw)
        assertNotNull(p)
        assertEquals("vzr_x", p!!.token)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val raw = """{"v":2,"url":"https://example.com","token":"vzr_x"}"""
        assertNull(EnrollmentPayload.parse(raw))
    }

    @Test
    fun rejectsMissingFields() {
        assertNull(EnrollmentPayload.parse("""{"v":1,"url":"https://x"}"""))
        assertNull(EnrollmentPayload.parse("""{"v":1,"token":"vzr_x"}"""))
        assertNull(EnrollmentPayload.parse("""{"url":"https://x","token":"vzr_x"}"""))
    }

    @Test
    fun rejectsNonHttpUrls() {
        val raw = """{"v":1,"url":"javascript:alert(1)","token":"vzr_x"}"""
        assertNull(EnrollmentPayload.parse(raw))
    }

    @Test
    fun rejectsBlankToken() {
        val raw = """{"v":1,"url":"https://x","token":""}"""
        assertNull(EnrollmentPayload.parse(raw))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(EnrollmentPayload.parse("not json"))
        assertNull(EnrollmentPayload.parse(""))
        assertNull(EnrollmentPayload.parse("{"))
    }
}
