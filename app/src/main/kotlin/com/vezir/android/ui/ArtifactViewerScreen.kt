package com.vezir.android.ui

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vezir.android.data.Prefs
import com.vezir.android.net.SessionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ArtifactViewerScreen(
    prefs: Prefs,
    sessionId: String,
    artifactName: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = remember { SessionApi(prefs.serverUrl!!, prefs.token!!, prefs.caPem) }

    var content by remember { mutableStateOf<ByteArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val isPdf = artifactName.endsWith(".pdf", ignoreCase = true)
    val isText = !isPdf

    LaunchedEffect(sessionId, artifactName) {
        loading = true
        errorMsg = null
        when (val result = api.downloadArtifact(sessionId, artifactName)) {
            is SessionApi.Result.Ok -> {
                content = result.data
                if (isPdf) {
                    // Write to cache and open with external viewer.
                    withContext(Dispatchers.IO) {
                        val f = File(context.cacheDir, artifactName)
                        f.writeBytes(result.data)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            f,
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            errorMsg = "No PDF viewer app installed"
                        }
                    }
                }
            }
            is SessionApi.Result.HttpError ->
                errorMsg = "Server error: ${result.code} ${result.message}"
            is SessionApi.Result.NetworkError ->
                errorMsg = "Network error: ${result.cause.message}"
        }
        loading = false
    }

    ScreenScaffold {
        // Top bar.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                artifactName,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            // Share button.
            if (content != null) {
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val f = File(context.cacheDir, artifactName)
                            f.writeBytes(content!!)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                f,
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = if (isPdf) "application/pdf" else "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(intent, "Share $artifactName"),
                            )
                        }
                    }
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share")
                }
                // Download button.
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                val values = ContentValues().apply {
                                    put(MediaStore.Downloads.DISPLAY_NAME, artifactName)
                                    put(
                                        MediaStore.Downloads.MIME_TYPE,
                                        if (isPdf) "application/pdf" else "text/plain",
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        put(
                                            MediaStore.Downloads.RELATIVE_PATH,
                                            Environment.DIRECTORY_DOWNLOADS + "/Vezir",
                                        )
                                    }
                                }
                                val uri = context.contentResolver.insert(
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                                )
                                if (uri != null) {
                                    context.contentResolver.openOutputStream(uri)?.use {
                                        it.write(content!!)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        Toast.makeText(context, "Saved to Downloads/Vezir/", Toast.LENGTH_SHORT)
                            .show()
                    }
                }) {
                    Icon(Icons.Filled.Download, contentDescription = "Download")
                }
            }
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            return@ScreenScaffold
        }

        if (errorMsg != null) {
            MonoStatus(errorMsg!!, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onBack) { Text("Back") }
            return@ScreenScaffold
        }

        if (isPdf) {
            Text(
                "PDF opened in external viewer",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@ScreenScaffold
        }

        // Inline text viewer for .txt, .srt, .json, .summary.md.
        val textContent = remember(content) {
            content?.toString(Charsets.UTF_8) ?: ""
        }

        SelectionContainer {
            Text(
                textContent,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
