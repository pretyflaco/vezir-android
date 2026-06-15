package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.IOException

/**
 * Polls GET /api/sessions/{id} every [intervalMs] until the server reports
 * a terminal state. Server schema: vezir/server/queue.py:50-57:
 *
 *     queued | transcribing | needs_labeling | syncing | done | error
 *
 * 'done' and 'error' are terminal. The Linux GUI polls at 5s; we match
 * that to keep server load identical to one extra desktop client.
 */
class SessionPoller(
    private val baseUrl: String,
    private val token: String,
    private val teamId: String?,
    caPem: String? = null,
    private val intervalMs: Long = 5_000L,
) {
    companion object {
        private val TERMINAL = setOf("done", "error")
        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * Thrown out of [poll] when the server answers a status request with
     * 401 — the session JWT has almost certainly expired mid-flow.  The
     * collector surfaces this as a "please sign in again" prompt instead
     * of polling forever (the pre-fix behaviour, which silently retried).
     */
    class AuthExpiredException : Exception("server returned 401; session likely expired")

    @Serializable
    data class SessionStatus(
        val id: String,
        val github: String? = null,
        val title: String? = null,
        val status: String,
        val created_at: String? = null,
        val updated_at: String? = null,
        val error: String? = null,
        val summary_error: String? = null,
        val sync_error: String? = null,
    ) {
        val isTerminal: Boolean get() = status in TERMINAL
    }

    private val client = HttpClients.build(caPem)

    /** Cold flow that emits status updates until terminal, then completes. */
    fun poll(sessionId: String): Flow<SessionStatus> = flow {
        var lastStatus: String? = null
        while (true) {
            val s = fetchOnce(sessionId)
            if (s != null) {
                if (s.status != lastStatus) emit(s)
                lastStatus = s.status
                if (s.isTerminal) return@flow
            }
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    fun fetchOnce(sessionId: String): SessionStatus? = try {
        val req = HttpClients.authHeaders(
            Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId"),
            token, teamId,
        ).get().build()
        client.newCall(req).execute().use { resp ->
            // 401 is terminal: the session expired. Surface it (don't
            // swallow into a null + infinite silent retry).
            if (resp.code == 401) throw AuthExpiredException()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            json.decodeFromString(SessionStatus.serializer(), body)
        }
    } catch (e: AuthExpiredException) {
        throw e // propagate out of poll() to the collector
    } catch (_: IOException) {
        null
    } catch (_: Throwable) {
        null
    }
}
