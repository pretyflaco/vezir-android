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
        // v1/v2/v3 are currently supported; v99 is not.
        val raw = """{"v":99,"url":"https://example.com","token":"vzr_x"}"""
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
    fun parsesV3EnrollmentCode() {
        val raw = """{"v":3,"url":"https://example.com","enroll_code":"vzre_abc","ca_pem":"pem"}"""
        val p = EnrollmentPayload.parse(raw)
        assertNotNull(p)
        assertEquals("vzre_abc", p!!.enroll_code)
        assertNull(p.token)
        assertEquals("pem", p.ca_pem)
    }

    @Test
    fun parsesHistoricalV3BearerAndAlternateUrls() {
        val raw = """{"v":3,"url":"https://example.com","token":"vzr_x","alt_urls":["http://100.64.0.1:8000"]}"""
        val p = EnrollmentPayload.parse(raw)
        assertNotNull(p)
        assertEquals("vzr_x", p!!.token)
        assertNull(p.enroll_code)
        assertEquals(listOf("http://100.64.0.1:8000"), p.alt_urls)
    }

    @Test
    fun rejectsAmbiguousCredentialShapes() {
        assertNull(EnrollmentPayload.parse("""{"v":3,"url":"https://x","token":"vzr_x","enroll_code":"vzre_x"}"""))
        assertNull(EnrollmentPayload.parse("""{"v":3,"url":"https://x","token":" ","enroll_code":"vzre_x"}"""))
    }

    @Test
    fun rejectsMissingOrBlankV3Credential() {
        assertNull(EnrollmentPayload.parse("""{"v":3,"url":"https://x"}"""))
        assertNull(EnrollmentPayload.parse("""{"v":3,"url":"https://x","enroll_code":" "}"""))
    }

    @Test
    fun rejectsEnrollmentCodeBeforeV3() {
        assertNull(EnrollmentPayload.parse("""{"v":1,"url":"https://x","enroll_code":"vzre_x"}"""))
        assertNull(EnrollmentPayload.parse("""{"v":2,"url":"https://x","enroll_code":"vzre_x"}"""))
    }

    @Test
    fun rejectsInvalidAlternateUrls() {
        assertNull(EnrollmentPayload.parse("""{"v":3,"url":"https://x","token":"vzr_x","alt_urls":["ftp://x"]}"""))
        assertNull(EnrollmentPayload.parse("""{"v":3,"url":"https://x","token":"vzr_x","alt_urls":[" "]}"""))
    }

    @Test
    fun rejectsMalformedHttpUrl() {
        assertNull(EnrollmentPayload.parse("""{"v":3,"url":"http://","enroll_code":"vzre_x"}"""))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(EnrollmentPayload.parse("not json"))
        assertNull(EnrollmentPayload.parse(""))
        assertNull(EnrollmentPayload.parse("{"))
    }
}
