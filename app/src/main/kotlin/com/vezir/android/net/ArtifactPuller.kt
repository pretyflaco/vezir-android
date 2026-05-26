package com.vezir.android.net

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Downloads meeting artifacts from the server into device-visible
 * storage at `Documents/Vezir/<team>/meeting-YYYYMMDD-HHMMSS_TITLE/`.
 *
 * Mirrors the desktop `vezir pull` command.  Idempotent: sessions
 * already pulled (tracked in a manifest file) are skipped.
 *
 * v0.4.0: initial implementation.
 */
class ArtifactPuller(
    private val api: SessionApi,
    private val context: Context,
    private val teamLabel: String,
) {
    companion object {
        private const val MANIFEST_NAME = "pull-manifest.json"
        private val json = Json { ignoreUnknownKeys = true }

        // Friendly names matching desktop vezir/client/artifacts.py
        private val FRIENDLY_NAMES = mapOf(
            "summary" to "summary.md",
            "txt" to "transcript.txt",
            "srt" to "transcript.srt",
            "pdf" to "transcript.pdf",
            "json" to "transcript.json",
        )
    }

    data class PullProgress(
        val total: Int,
        val pulled: Int,
        val current: String,
    )

    @Serializable
    private data class Manifest(
        val pulled: MutableMap<String, String> = mutableMapOf(),
    )

    private val manifestFile: File
        get() = File(context.filesDir, "pull-manifests/$teamLabel/$MANIFEST_NAME")

    private fun loadManifest(): Manifest {
        val f = manifestFile
        if (!f.exists()) return Manifest()
        return try {
            json.decodeFromString(Manifest.serializer(), f.readText())
        } catch (_: Exception) {
            Manifest()
        }
    }

    private fun saveManifest(m: Manifest) {
        val f = manifestFile
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(Manifest.serializer(), m))
    }

    /**
     * Pull artifacts for recent team sessions.
     * Returns number of sessions successfully pulled.
     */
    suspend fun pullTeamSessions(
        limit: Int = 50,
        since: String? = null,
        onProgress: (PullProgress) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val result = api.getSessions(limit = limit, since = since)
        val sessions = when (result) {
            is SessionApi.Result.Ok -> result.data
            else -> return@withContext 0
        }

        val pullable = sessions.filter { it.status == "done" && it.artifactMap.isNotEmpty() }
        if (pullable.isEmpty()) return@withContext 0

        val manifest = loadManifest()
        var pulled = 0

        for (session in pullable) {
            if (session.id in manifest.pulled) continue

            val dirname = dirnameForSession(session)
            onProgress(PullProgress(pullable.size, pulled, dirname))

            val savedCount = downloadSessionArtifacts(session, dirname)
            if (savedCount > 0) {
                manifest.pulled[session.id] = dirname
                saveManifest(manifest)
                pulled++
            }
        }

        onProgress(PullProgress(pullable.size, pulled, "done"))
        pulled
    }

    /**
     * Pull artifacts for a single session.
     */
    suspend fun pullSingleSession(sessionId: String): Int =
        withContext(Dispatchers.IO) {
            val result = api.getSession(sessionId)
            val session = when (result) {
                is SessionApi.Result.Ok -> result.data
                else -> return@withContext 0
            }
            if (session.artifactMap.isEmpty()) return@withContext 0
            val dirname = dirnameForSession(session)
            val saved = downloadSessionArtifacts(session, dirname)
            if (saved > 0) {
                val manifest = loadManifest()
                manifest.pulled[session.id] = dirname
                saveManifest(manifest)
            }
            saved
        }

    private suspend fun downloadSessionArtifacts(
        session: SessionApi.Session,
        dirname: String,
    ): Int {
        var saved = 0
        for ((key, serverName) in session.artifactMap) {
            val friendlyName = FRIENDLY_NAMES[key] ?: serverName
            val result = api.downloadArtifact(session.id, serverName)
            if (result is SessionApi.Result.Ok && result.data.isNotEmpty()) {
                if (saveToDocuments(dirname, friendlyName, result.data)) {
                    saved++
                }
            }
        }
        // Write session metadata.
        val meta = """
            |{
            |  "session_id": "${session.id}",
            |  "title": ${session.title?.let { "\"$it\"" } ?: "null"},
            |  "status": "${session.status}",
            |  "github": ${session.github?.let { "\"$it\"" } ?: "null"},
            |  "created_at": ${session.created_at?.let { "\"$it\"" } ?: "null"},
            |  "pulled_by": "vezir-android"
            |}
        """.trimMargin()
        saveToDocuments(dirname, "session.json", meta.toByteArray())
        return saved
    }

    private fun saveToDocuments(dirname: String, fileName: String, data: ByteArray): Boolean {
        return try {
            val mimeType = when {
                fileName.endsWith(".pdf") -> "application/pdf"
                fileName.endsWith(".json") -> "application/json"
                fileName.endsWith(".md") -> "text/markdown"
                else -> "text/plain"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOCUMENTS}/Vezir/$teamLabel/$dirname",
                    )
                }
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
            ) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun dirnameForSession(session: SessionApi.Session): String {
        val ts = session.created_at ?: ""
        val dateStr = try {
            val dt = ZonedDateTime.parse(
                ts.replace("Z", "+00:00").let {
                    if ("T" in it) it else "${it}T00:00:00+00:00"
                },
            )
            dt.withZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        } catch (_: Exception) {
            "unknown"
        }

        val title = session.title?.trim() ?: ""
        val slug = title
            .replace(Regex("[^a-zA-Z0-9]+"), "_")
            .trim('_')
            .uppercase()
            .take(60)

        return if (slug.isNotEmpty()) "meeting-${dateStr}_$slug" else "meeting-$dateStr"
    }
}
