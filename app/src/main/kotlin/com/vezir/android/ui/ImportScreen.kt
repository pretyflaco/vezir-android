package com.vezir.android.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vezir.android.capture.ImportController

/**
 * Pick an existing audio/video file and turn it into an OGG ready for
 * upload. The user lands here from RecordScreen's "Import existing
 * recording" button.
 *
 * Flow:
 *   1. Auto-launch SAF OpenDocument picker filtered to audio/video MIME.
 *   2. On result, kick [ImportController.startImport].
 *   3. Show a progress bar while [AudioImporter] transcodes (or
 *      stream-copies, on the OGG passthrough path).
 *   4. On DONE, [onImported] is called with the resulting URI and the
 *      caller routes to UploadScreen. On ERROR/cancel, [onCancel].
 */
@Composable
fun ImportScreen(
    onCancel: () -> Unit,
    onImported: (uri: Uri, fileName: String) -> Unit,
) {
    val context = LocalContext.current
    val snapshot by ImportController.state.collectAsState()

    // OpenDocument with multiple MIME types: audio/* and video/* (so
    // Samsung screen recordings, voice memos, prior Vezir OGGs, and
    // arbitrary YouTube-likes all work).
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            onCancel()
            return@rememberLauncherForActivityResult
        }
        // Persist read access in case the user backgrounds before the
        // import finishes. Best-effort.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = querySafName(context, uri) ?: "imported"
        ImportController.startImport(context, uri, name)
    }

    var launched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!launched && snapshot.state == ImportController.State.IDLE) {
            launched = true
            pickFile.launch(arrayOf("audio/*", "video/*"))
        }
    }

    // Auto-route on success.
    LaunchedEffect(snapshot.state, snapshot.resultUri) {
        if (snapshot.state == ImportController.State.DONE && snapshot.resultUri != null) {
            val uri = snapshot.resultUri!!
            val name = snapshot.resultDisplayName ?: "vezir-import.ogg"
            // Reset before navigating so the controller is IDLE if the
            // user comes back to import another file.
            ImportController.acknowledge()
            onImported(uri, name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Import recording", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pick an existing audio or video file. Vezir will encode it to " +
                "OGG/Opus on-device and upload it through the same pipeline.",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (snapshot.state) {
            ImportController.State.IDLE -> {
                Text("Waiting for picker...", style = MaterialTheme.typography.bodySmall)
            }
            ImportController.State.IMPORTING -> {
                Text(
                    "Importing ${snapshot.sourceName ?: "(unnamed)"}...",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                LinearProgressIndicator(
                    progress = { snapshot.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "%.0f%%".format(snapshot.progress * 100f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            ImportController.State.DONE -> {
                Text(
                    "Imported. Switching to upload...",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ImportController.State.ERROR -> {
                Text(
                    "Error: ${snapshot.errorMessage ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        OutlinedButton(
            onClick = {
                ImportController.reset()
                onCancel()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (snapshot.state) {
                    ImportController.State.ERROR -> "Back"
                    ImportController.State.IMPORTING -> "Cancel"
                    else -> "Cancel"
                }
            )
        }
    }
}

private fun querySafName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    } catch (_: Exception) { null }
}
