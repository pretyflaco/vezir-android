package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Best-effort server-side logout via `POST /api/auth/logout`
 * (vezir server >= 0.10.0).
 *
 * Revokes the caller's own session family so its refresh token can no
 * longer mint access tokens.  Deliberately fire-and-forget: sign-out must
 * succeed locally even if the server is unreachable or predates the
 * endpoint, so all failures are swallowed.
 */
class LogoutApi(
    private val baseUrl: String,
    private val token: String,
    caPem: String? = null,
) {
    // No refresh-on-401 here: a 401 during logout just means the token
    // already lapsed, which is fine — nothing to revoke.
    private val client = HttpClients.build(caPem, refreshOn401 = false)

    /** POST /api/auth/logout. Returns true on a 2xx, false otherwise. */
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/auth/logout")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody(null))
            .build()
        try {
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (_: IOException) {
            false
        }
    }
}
