package com.vezir.android.ui

import android.content.Intent
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
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by UploadController.state.collectAsState()

    LaunchedEffect(contentUri, fileName) {
        val url = prefs.serverUrl
        val token = prefs.token
        if (url.isNullOrBlank() || token.isNullOrBlank()) return@LaunchedEffect
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
            "to ${prefs.serverUrl ?: "(unset)"}",
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
            if (snapshot.errorMessage != null) {
                MonoStatus(
                    "error: ${snapshot.errorMessage}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (snapshot.dashboardLoginUrl != null &&
            (snapshot.state == UploadController.State.POLLING ||
                snapshot.state == UploadController.State.DONE)) {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW,
                        Uri.parse(snapshot.dashboardLoginUrl))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Open in dashboard") }
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

private fun formatKib(bytes: Long): String =
    if (bytes < 1024) "$bytes B"
    else "%.1f KiB".format(bytes / 1024.0)
