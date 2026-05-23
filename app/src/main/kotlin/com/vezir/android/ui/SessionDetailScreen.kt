package com.vezir.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.vezir.android.data.Prefs
import com.vezir.android.net.SessionApi
import com.vezir.android.net.SessionPoller
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SessionDetailScreen(
    prefs: Prefs,
    sessionId: String,
    onBack: () -> Unit,
    onLabel: (String) -> Unit,
    onArtifact: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = remember { SessionApi(prefs.serverUrl!!, prefs.token!!, prefs.caPem) }

    var session by remember { mutableStateOf<SessionApi.Session?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var actionBusy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = session == null
            errorMsg = null
            when (val result = api.getSession(sessionId)) {
                is SessionApi.Result.Ok -> session = result.data
                is SessionApi.Result.HttpError ->
                    errorMsg = "Server error: ${result.code} ${result.message}"
                is SessionApi.Result.NetworkError ->
                    errorMsg = "Network error: ${result.cause.message}"
            }
            loading = false
        }
    }

    LaunchedEffect(sessionId) { refresh() }

    // Auto-poll while non-terminal.
    LaunchedEffect(session?.status) {
        val s = session ?: return@LaunchedEffect
        if (s.isTerminal) return@LaunchedEffect
        while (true) {
            delay(5_000)
            refresh()
        }
    }

    ScreenScaffold {
        // Top bar with back button.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Session",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            session?.let { StatusBadge(it.status) }
        }

        if (loading && session == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            return@ScreenScaffold
        }

        if (errorMsg != null && session == null) {
            MonoStatus(errorMsg!!, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { refresh() }) { Text("Retry") }
            return@ScreenScaffold
        }

        val s = session ?: return@ScreenScaffold

        // Metadata.
        Text(
            s.title ?: "untitled",
            style = MaterialTheme.typography.titleMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MonoStatus(
                "id ${s.id}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MonoStatus(
                "by ${s.github ?: "?"}  created ${s.created_at?.take(16) ?: "?"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.summary_preset != null) {
                MonoStatus(
                    "preset ${s.summary_preset}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.isPersonal) {
                MonoStatus(
                    "personal (not visible to team)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (s.error != null) {
            MonoStatus(
                "error: ${s.error}",
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (s.summary_error != null) {
            MonoStatus(
                "summary unavailable: transcription succeeded but the " +
                    "summary backend was unreachable. Transcript artifacts " +
                    "are available below.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (s.sync_error != null) {
            MonoStatus(
                "sync failed: artifacts are on the server but not " +
                    "pushed to git. Use 'Sync now' to retry.",
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Artifacts.
        val artifacts = s.artifactMap
        if (artifacts.isNotEmpty()) {
            Text(
                "Artifacts",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            artifacts.forEach { (type, name) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArtifact(sessionId, name) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = type,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace)
                            Text(type, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Action buttons.
        if (s.summary_error != null && s.status == "done") {
            Button(
                onClick = {
                    scope.launch {
                        actionBusy = true
                        api.retrySummary(sessionId)
                        refresh()
                        actionBusy = false
                    }
                },
                enabled = !actionBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Retry summary") }
        }

        if (s.status in listOf("needs_labeling", "done", "error")) {
            Button(
                onClick = { onLabel(sessionId) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(
                    if (s.status == "needs_labeling") "Label speakers"
                    else "Re-label speakers",
                )
            }
        }

        if (s.isPersonal) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        actionBusy = true
                        api.shareWithTeam(sessionId)
                        refresh()
                        actionBusy = false
                    }
                },
                enabled = !actionBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share with team") }
        }

        if (s.status == "done" && ((s.sync_enabled ?: 1) == 0 || s.sync_error != null)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        actionBusy = true
                        api.syncNow(sessionId)
                        refresh()
                        actionBusy = false
                    }
                },
                enabled = !actionBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync now") }
        }

        OutlinedButton(
            onClick = {
                val url = "${prefs.serverUrl?.trimEnd('/')}/s/$sessionId"
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open in browser") }
    }
}
