package com.vezir.android.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rotating-refresh-token support in the credential store:
 *  * `TeamCredential` gains `refreshToken` / `accessExpiresAt` and still
 *    deserialises older JSON that lacks them (back-compat).
 *  * `applyRefreshedToken` rotates every entry sharing the old access
 *    token (a vezir session is one family shared across the user's teams)
 *    and leaves unrelated entries untouched.
 */
class TeamCredentialRefreshTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Plain in-memory backing so we don't need Android EncryptedSharedPreferences. */
    private class FakeBacking : TeamCredentialBacking {
        override var teamsJson: String? = null
        override var activeTeamId: String? = null
    }

    @Test
    fun deserialisesLegacyJsonWithoutRefreshFields() {
        // Pre-0.7.0 persisted entry: no refreshToken / accessExpiresAt.
        val legacy = """
            [{"id":"team-a","url":"https://s","token":"eyJ.a.b","label":"Team A"}]
        """.trimIndent()
        val list = json.decodeFromString<List<TeamCredential>>(legacy)
        assertEquals(1, list.size)
        assertNull(list[0].refreshToken)
        assertEquals(0L, list[0].accessExpiresAt)
    }

    @Test
    fun applyRefreshedToken_rotatesAllTeamsSharingOldToken() {
        val store = TeamCredentialStore(FakeBacking())
        val old = "eyJ.old.access"
        // Two teams from one login share the old token + refresh token.
        store.addOrUpdate(
            TeamCredential("team-a", "https://s", old, refreshToken = "vzrt_1"),
            activate = true,
        )
        store.addOrUpdate(
            TeamCredential("team-b", "https://s", old, refreshToken = "vzrt_1"),
        )
        // An unrelated team on a separate vzr_ token must not be touched.
        store.addOrUpdate(
            TeamCredential("other", "https://x", "vzr_static", refreshToken = null),
        )

        val n = store.applyRefreshedToken(
            oldToken = old,
            newToken = "eyJ.new.access",
            newRefreshToken = "vzrt_2",
            accessExpiresAt = 12345L,
        )

        assertEquals(2, n)
        val teams = store.loadAll().associateBy { it.id }
        assertEquals("eyJ.new.access", teams["team-a"]!!.token)
        assertEquals("vzrt_2", teams["team-a"]!!.refreshToken)
        assertEquals(12345L, teams["team-a"]!!.accessExpiresAt)
        assertEquals("eyJ.new.access", teams["team-b"]!!.token)
        assertEquals("vzrt_2", teams["team-b"]!!.refreshToken)
        // Untouched:
        assertEquals("vzr_static", teams["other"]!!.token)
        assertNull(teams["other"]!!.refreshToken)
    }

    @Test
    fun applyRefreshedToken_noMatch_returnsZero() {
        val store = TeamCredentialStore(FakeBacking())
        store.addOrUpdate(
            TeamCredential("team-a", "https://s", "eyJ.current", refreshToken = "vzrt_1"),
            activate = true,
        )
        val n = store.applyRefreshedToken(
            oldToken = "eyJ.some.other.token",
            newToken = "eyJ.new",
            newRefreshToken = "vzrt_2",
            accessExpiresAt = 1L,
        )
        assertEquals(0, n)
        assertEquals("eyJ.current", store.getActive()!!.token)
    }

    @Test
    fun applyRefreshedToken_nullNewRefreshKeepsExisting() {
        val store = TeamCredentialStore(FakeBacking())
        store.addOrUpdate(
            TeamCredential("team-a", "https://s", "old", refreshToken = "vzrt_keep"),
            activate = true,
        )
        store.applyRefreshedToken("old", "new", newRefreshToken = null, accessExpiresAt = 0L)
        assertEquals("vzrt_keep", store.getActive()!!.refreshToken)
    }
}
