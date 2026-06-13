package com.vezir.android.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden-vector tests for the NIP-98 canonical serialisation + event id.
 *
 * The expected values were produced by the vezir server's own
 * canonicalisation (`json.dumps([0,pubkey,created_at,kind,tags,content],
 * separators=(",",":"), ensure_ascii=False)` → sha256), so a match here
 * proves the Android client computes a byte-identical id — required for
 * the server to accept the signed event.
 */
class Nip98EventTest {

    private val pubkey = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val createdAt = 1700000000L
    private val tags = listOf(
        listOf("u", "https://vezir.twentyone.ist/api/auth/nostr/login"),
        listOf("method", "POST"),
    )

    @Test
    fun canonicalJson_matchesServer() {
        val expected =
            "[0,\"3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d\"," +
                "1700000000,27235," +
                "[[\"u\",\"https://vezir.twentyone.ist/api/auth/nostr/login\"]," +
                "[\"method\",\"POST\"]],\"\"]"
        val actual = Nip98Event.canonicalJson(pubkey, createdAt, Nip98Event.KIND, tags, "")
        assertEquals(expected, actual)
    }

    @Test
    fun computeId_matchesServerGoldenVector() {
        val id = Nip98Event.computeId(pubkey, createdAt, Nip98Event.KIND, tags, "")
        assertEquals("718c9fa6b3bcb2329430ce0e44b45a18a6d5c4f445b4adcd5e21daaf17e26a08", id)
    }

    @Test
    fun computeId_unicodeAndEscapesMatchServer() {
        // Mirrors the server vector with non-ASCII + escaped chars in content.
        val content = "héllo \"quote\" \\ and \n newline"
        val id = Nip98Event.computeId(pubkey, createdAt, Nip98Event.KIND, tags, content)
        assertEquals("fc27e6ec3e45447578bcef1bc96b219f1248fc6d3d296ed52fe9a9f3f1ce6c28", id)
    }

    @Test
    fun buildLogin_setsTagsAndId() {
        val u = Nip98Event.buildLogin(
            pubkeyHex = pubkey,
            loginUrl = "https://vezir.twentyone.ist/api/auth/nostr/login",
            createdAt = createdAt,
        )
        assertEquals(Nip98Event.KIND, u.kind)
        assertEquals(pubkey, u.pubkey)
        assertEquals("u", u.tags[0][0])
        assertEquals("https://vezir.twentyone.ist/api/auth/nostr/login", u.tags[0][1])
        assertEquals("method", u.tags[1][0])
        assertEquals("POST", u.tags[1][1])
        assertEquals("718c9fa6b3bcb2329430ce0e44b45a18a6d5c4f445b4adcd5e21daaf17e26a08", u.id)
    }
}
