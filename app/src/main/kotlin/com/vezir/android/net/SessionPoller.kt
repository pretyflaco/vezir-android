package com.vezir.android.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    private val intervalMs: Long = 5_000L,
) {
    companion object {
        private val TERMINAL = setOf("done", "error")
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    data class SessionStatus(
        val id: String,
        val github: String? = null,
        val title: String? = null,
        val status: String,
        val created_at: String? = null,
        val updated_at: String? = null,
        val error: String? = null,
    ) {
        val isTerminal: Boolean get() = status in TERMINAL
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/sessions/$sessionId")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            json.decodeFromString(SessionStatus.serializer(), body)
        }
    } catch (_: IOException) {
        null
    } catch (_: Throwable) {
        null
    }
}
