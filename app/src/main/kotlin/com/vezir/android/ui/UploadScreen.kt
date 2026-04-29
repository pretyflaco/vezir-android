package com.vezir.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vezir.android.data.Prefs
import com.vezir.android.net.UploadController

/**
 * Uploads a recording to the configured Vezir server, then polls
 * /api/sessions/{id} until the worker reports done/error.
 *
 * Triggers automatically on first composition with a non-null
 * [contentUri]; the caller is responsible for clearing the URI when
 * they want to dismiss/return to the recorder.
 */
@Composable
fun UploadScreen(
    prefs: Prefs,
    contentUri: Uri,
    fileName: String,
    title: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by UploadController.state.collectAsState()

    LaunchedEffect(contentUri, fileName) {
        val url = prefs.serverUrl
        val token = prefs.token
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            return@LaunchedEffect
        }
        // If the controller is already finished/erroring on the same
        // sessionId, leave it alone; otherwise start a fresh upload.
        val s = UploadController.state.value
        if (s.state == UploadController.State.IDLE) {
            UploadController.startUpload(
                baseUrl = url,
                token = token,
                contentResolver = context.contentResolver,
                contentUri = contentUri,
                fileName = fileName,
                title = title,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Upload", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sending $fileName to ${prefs.serverUrl ?: "(unset)"}.",
            style = MaterialTheme.typography.bodyMedium,
        )

        ProgressBlock(snapshot)

        when (snapshot.state) {
            UploadController.State.IDLE,
            UploadController.State.UPLOADING -> {
                // No actions; cancel via system back / dismiss
            }
            UploadController.State.POLLING,
            UploadController.State.DONE -> {
                if (snapshot.dashboardLoginUrl != null) {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(snapshot.dashboardLoginUrl))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open in dashboard") }
                }
            }
            UploadController.State.ERROR -> {
                Text(
                    "Error: ${snapshot.errorMessage ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

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

@Composable
private fun ProgressBlock(snapshot: UploadController.Snapshot) {
    val pct = if (snapshot.totalBytes > 0)
        (snapshot.sentBytes.toFloat() / snapshot.totalBytes.toFloat()).coerceIn(0f, 1f)
    else 0f
    val sentKib = snapshot.sentBytes / 1024.0
    val totKib = snapshot.totalBytes / 1024.0

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "State: ${snapshot.state.name}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        if (snapshot.attempt > 1 && snapshot.state == UploadController.State.UPLOADING) {
            Text(
                "Retry ${snapshot.attempt}/${snapshot.maxAttempts} (restarted from byte 0)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (snapshot.totalBytes > 0) {
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "%.1f / %.1f KiB  (%.1f%%)".format(sentKib, totKib, pct * 100),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (snapshot.sessionId != null) {
            Text(
                "Session: ${snapshot.sessionId}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (snapshot.serverStatus != null) {
            Text(
                "Server status: ${snapshot.serverStatus}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (snapshot.serverError != null) {
            Text(
                "Server error: ${snapshot.serverError}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
