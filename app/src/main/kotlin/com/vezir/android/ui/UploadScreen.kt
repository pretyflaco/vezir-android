package com.vezir.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vezir.android.data.Prefs
import com.vezir.android.net.UploadController

@Composable
fun UploadScreen(
    prefs: Prefs,
    contentUri: Uri,
    fileName: String,
    title: String?,
    summaryPreset: String?,
    autoLabel: Boolean,
    sync: Boolean,
    personal: Boolean = false,
    onDismiss: () -> Unit,
    onLabel: ((String) -> Unit)? = null,
    onSessionDetail: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val snapshot by UploadController.state.collectAsState()

    val cred = remember { prefs.activeCredential() }

    // v0.4.4: probe /health to find a reachable URL before uploading.
    // The Uploader has its own retry logic + streaming, so we can't wrap
    // it with ResilientApi.execute{}.  Instead, resolve the URL upfront.
    LaunchedEffect(contentUri, fileName) {
        val c = cred ?: return@LaunchedEffect
        val s = UploadController.state.value
        if (s.state == UploadController.State.IDLE) {
            val resilient = com.vezir.android.net.ResilientApi(
                c.url, c.altUrls, c.token, c.id, c.caPem,
            )
            val uploadUrl = resilient.findReachableUrl() ?: c.url
            UploadController.startUpload(
                baseUrl = uploadUrl,
                token = c.token,
                teamId = c.id,
                contentResolver = context.contentResolver,
                contentUri = contentUri,
                fileName = fileName,
                title = title,
                summaryPreset = summaryPreset,
                autoLabel = autoLabel,
                sync = sync,
                personal = personal,
                caPem = c.caPem,
            )
        }
    }

    // Auto-delete the local recording when upload + processing succeeds.
    LaunchedEffect(snapshot.state, snapshot.serverStatus) {
        if (snapshot.state == UploadController.State.DONE &&
            snapshot.serverStatus == "done" &&
            prefs.autoDeleteAfterUpload) {
            try {
                context.contentResolver.delete(contentUri, null, null)
            } catch (_: Exception) {}
        }
    }

    // v0.4.0: auto-download artifacts when processing completes.
    LaunchedEffect(snapshot.state, snapshot.serverStatus) {
        if (snapshot.state == UploadController.State.DONE &&
            snapshot.serverStatus == "done" &&
            snapshot.sessionId != null) {
            val c = cred ?: return@LaunchedEffect
            val api = com.vezir.android.net.ResilientApi(
                c.url, c.altUrls, c.token, c.id, c.caPem,
            )
            val puller = com.vezir.android.net.ArtifactPuller(
                api, context, prefs.activeTeamId ?: "default",
            )
            try {
                puller.pullSingleSession(snapshot.sessionId!!)
            } catch (_: Exception) {}
        }
    }

    val pct = if (snapshot.totalBytes > 0)
        (snapshot.sentBytes.toFloat() / snapshot.totalBytes.toFloat()).coerceIn(0f, 1f)
    else 0f

    ScreenScaffold {
        CompactBrandHeader(title = "upload")

        Text(
            fileName,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "to ${cred?.url ?: "(unset)"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!summaryPreset.isNullOrBlank()) {
            Text(
                "preset $summaryPreset",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "auto-label=$autoLabel  sync=$sync",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Hero progress block.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "%.0f%%".format(pct * 100),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (snapshot.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth(),
                )
                MonoStatus(
                    "${formatKib(snapshot.sentBytes)} / ${formatKib(snapshot.totalBytes)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MonoStatus(
                "state ${snapshot.state.name.lowercase()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.attempt > 1 && snapshot.state == UploadController.State.UPLOADING) {
                MonoStatus(
                    "retry ${snapshot.attempt}/${snapshot.maxAttempts}; restarted from byte 0",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (snapshot.sessionId != null) {
                MonoStatus(
                    "session ${snapshot.sessionId}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snapshot.serverStatus != null) {
                MonoStatus(
                    "server ${snapshot.serverStatus}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (snapshot.serverError != null) {
                MonoStatus(
                    "server error: ${snapshot.serverError}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (snapshot.summaryError != null) {
                MonoStatus(
                    "summary unavailable (transcript OK). " +
                        "View session to retry.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (snapshot.syncError != null) {
                MonoStatus(
                    "sync failed (artifacts OK). " +
                        "View session to retry.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (snapshot.errorMessage != null) {
                MonoStatus(
                    "error: ${snapshot.errorMessage}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Native labeling button: appears when server reports needs_labeling.
        if (snapshot.serverStatus == "needs_labeling" &&
            snapshot.sessionId != null &&
            onLabel != null) {
            Button(
                onClick = { onLabel(snapshot.sessionId!!) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Label speakers") }
        }

        // View session detail (native) — available once we have a session ID.
        if (snapshot.sessionId != null && onSessionDetail != null &&
            snapshot.state != UploadController.State.UPLOADING) {
            OutlinedButton(
                onClick = {
                    UploadController.reset()
                    onSessionDetail(snapshot.sessionId!!)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("View session") }
        }

        // v0.5.0: "Open in browser" removed; vezir 0.7.0 has no HTML
        // dashboard.  Use "View session" above instead.

        OutlinedButton(
            onClick = {
                UploadController.reset()
                onDismiss()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (snapshot.state) {
                    UploadController.State.UPLOADING -> "Cancel and back"
                    UploadController.State.DONE,
                    UploadController.State.ERROR -> "Done"
                    else -> "Back"
                }
            )
        }
    }
}

private fun formatKib(bytes: Long): String =
    if (bytes < 1024) "$bytes B"
    else "%.1f KiB".format(bytes / 1024.0)
