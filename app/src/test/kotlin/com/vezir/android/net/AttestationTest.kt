package com.vezir.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attestation semantics (server >= 0.20.0).
 *
 * `isAttested` and `isUnattested` are deliberately NOT inverses: a session
 * with unknown provenance -- an older server, or no summary -- is neither.
 * Flagging those would put a warning on most of the archive and make it
 * meaningless.
 */
class AttestationTest {

    private fun session(prov: String?) = SessionApi.Session(
        id = "01SID",
        status = "done",
        summary_provenance = prov,
    )

    @Test
    fun `tee backends are attested`() {
        assertTrue(session("tinfoil/glm-5-3-flash (TEE)").isAttested)
        assertTrue(session("tinfoil-tee/whatever").isAttested)
        assertFalse(session("tinfoil/glm-5-3-flash (TEE)").isUnattested)
    }

    @Test
    fun `other backends are positively unattested`() {
        for (prov in listOf(
            "claudemax/claude-sonnet-4-6",
            "openrouter/kimi-k2.6",
            "openai/kimi-k3",
            "ollama/qwen3.8:27b",
        )) {
            assertFalse(prov, session(prov).isAttested)
            assertTrue(prov, session(prov).isUnattested)
        }
    }

    @Test
    fun `unknown provenance is neither attested nor flagged`() {
        for (prov in listOf(null, "")) {
            assertFalse(session(prov).isAttested)
            assertFalse(session(prov).isUnattested)
        }
    }

    @Test
    fun `provenance defaults to null against an older server`() {
        // The field is absent from a pre-0.20.0 response; deserialization
        // must not fail, it must just leave it unknown.
        val s = SessionApi.Session(id = "01SID", status = "done")
        assertEquals(null, s.summary_provenance)
        assertFalse(s.isAttested)
        assertFalse(s.isUnattested)
    }
}
