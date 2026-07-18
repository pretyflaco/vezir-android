package com.vezir.android.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vezir.android.capture.ImportController

@Composable
fun ImportScreen(
    onCancel: () -> Unit,
    onImported: (uri: Uri, fileName: String) -> Unit,
    onImportedMulti: (uris: List<Uri>, fileNames: List<String>) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val snapshot by ImportController.state.collectAsState()

    val pickFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) {
            onCancel()
            return@rememberLauncherForActivityResult
        }
        val sources = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            uri to (querySafName(context, uri) ?: "imported")
        }
        if (sources.size == 1) {
            ImportController.startImport(context, sources[0].first, sources[0].second)
        } else {
            ImportController.startMultiImport(context, sources)
        }
    }

    var launched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!launched && snapshot.state == ImportController.State.IDLE) {
            launched = true
            pickFiles.launch(arrayOf("audio/*", "video/*"))
        }
    }

    LaunchedEffect(snapshot.state, snapshot.resultUri) {
        if (snapshot.state == ImportController.State.DONE && snapshot.resultUri != null) {
            val uris = snapshot.resultUris
            val names = snapshot.resultNames
            ImportController.acknowledge()
            if (uris.size > 1) {
                onImportedMulti(uris, names)
            } else {
                val uri = snapshot.resultUri!!
                val name = snapshot.resultDisplayName ?: "vezir-import.ogg"
                onImported(uri, name)
            }
        }
    }

    ScreenScaffold {
        CompactBrandHeader(title = "import")

        Text(
            "Pick one or more existing audio or video files. Vezir encodes " +
                "each to OGG/Opus on-device. Multiple files are combined into " +
                "a single meeting, then sent through the same upload pipeline " +
                "as a fresh recording.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (snapshot.state) {
                ImportController.State.IDLE -> {
                    Text(
                        "Waiting for picker…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ImportController.State.IMPORTING -> {
                    Text(
                        "%.0f%%".format(snapshot.progress * 100f),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    LinearProgressIndicator(
                        progress = { snapshot.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (snapshot.partCount > 1) {
                        MonoStatus(
                            "file ${snapshot.partIndex} of ${snapshot.partCount}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MonoStatus(
                        snapshot.sourceName ?: "(unnamed)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ImportController.State.DONE -> {
                    MonoStatus("Imported. Switching to upload…")
                }
                ImportController.State.ERROR -> {
                    MonoStatus(
                        "error: ${snapshot.errorMessage ?: "unknown"}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
