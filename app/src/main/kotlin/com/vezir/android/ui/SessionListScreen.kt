package com.vezir.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vezir.android.data.Prefs
import com.vezir.android.data.TeamCredentialStore
import com.vezir.android.net.ArtifactPuller
import com.vezir.android.net.MeApi
import com.vezir.android.net.ResilientApi
import com.vezir.android.net.SessionApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SessionListScreen(
    prefs: Prefs,
    teamLabel: String? = null,
    teams: List<com.vezir.android.data.TeamCredential> = emptyList(),
    activeTeamId: String? = null,
    onSwitchTeam: ((String) -> Unit)? = null,
    onSessionClick: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cred = remember(prefs.activeTeamId) { prefs.activeCredential() }
    val api = remember(cred) {
        cred?.let { ResilientApi(it.url, it.altUrls, it.token, it.id, it.caPem) }
    }

    var sessions by remember { mutableStateOf<List<SessionApi.Session>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var pulling by remember { mutableStateOf(false) }
    var pullStatus by remember { mutableStateOf<String?>(null) }

    if (cred == null || api == null) {
        Column(modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Not configured", style = MaterialTheme.typography.bodyLarge)
            Text("Scan a QR code in the Settings tab to get started.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    fun refresh() {
        scope.launch {
            loading = true
            errorMsg = null
            when (val result = api.execute { it.getSessions() }) {
                is SessionApi.Result.Ok -> sessions = result.data
                is SessionApi.Result.HttpError ->
                    errorMsg = "Server error: ${result.code} ${result.message}"
                is SessionApi.Result.NetworkError ->
                    errorMsg = "Network error: ${result.cause.message}"
            }
            loading = false
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    fun pullTeamMeetings() {
        if (pulling || api == null || cred == null) return
        scope.launch {
            pulling = true
            pullStatus = "pulling..."
            val puller = ArtifactPuller(api, context, teamLabel ?: activeTeamId ?: "default")
            val pulled = puller.pullTeamSessions(
                limit = 50,
                onProgress = { p ->
                    pullStatus = if (p.current == "done") {
                        "${p.pulled} session(s) pulled"
                    } else {
                        "pulling ${p.pulled + 1}/${p.total}: ${p.current}"
                    }
                },
            )
            pullStatus = if (pulled > 0) "$pulled session(s) pulled" else "up to date"
            pulling = false
        }
    }

    // v0.4.3: ResilientApi probes /health on all URLs (primary + alts)
    // and falls back automatically.  We still retry on NetworkError in
    // case ALL URLs are down initially (VPN mesh establishing).
    LaunchedEffect(cred) {
        if (api == null) return@LaunchedEffect
        val maxRetries = 3
        for (attempt in 1..maxRetries) {
            loading = true
            errorMsg = null
            when (val result = api.execute { it.getSessions() }) {
                is SessionApi.Result.Ok -> {
                    sessions = result.data
                    loading = false
                    // Background refresh of alternate URLs from /api/me.
                    val c = cred
                    if (c != null) {
                        val meApi = MeApi(
                            api.lastGoodUrl, c.token, c.caPem,
                            externalClient = api.client,
                        )
                        val meResult = meApi.getMe()
                        if (meResult is SessionApi.Result.Ok) {
                            val me = meResult.data
                            if (me.alternate_urls != c.altUrls) {
                                val store = TeamCredentialStore(prefs)
                                store.getActive()?.let { current ->
                                    store.addOrUpdate(
                                        current.copy(altUrls = me.alternate_urls),
                                    )
                                }
                            }
                        }
                    }
                    return@LaunchedEffect
                }
                is SessionApi.Result.HttpError -> {
                    errorMsg = "Server error: ${result.code} ${result.message}"
                    loading = false
                    return@LaunchedEffect
                }
                is SessionApi.Result.NetworkError -> {
                    if (attempt >= maxRetries) {
                        errorMsg = "Network error: ${result.cause.message}"
                        loading = false
                    } else {
                        errorMsg = "Connecting to server (attempt $attempt/$maxRetries)..."
                        loading = false
                        delay(15_000L)
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactBrandHeader(
                title = "sessions",
                teamLabel = teamLabel,
                teams = teams,
                activeTeamId = activeTeamId,
                onSwitchTeam = onSwitchTeam,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { pullTeamMeetings() }, enabled = !pulling) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = "Pull team meetings",
                    tint = if (pulling) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        if (pullStatus != null) {
            MonoStatus(
                pullStatus!!,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (loading && sessions.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 32.dp),
            )
            return
        }

        if (errorMsg != null && sessions.isEmpty()) {
            MonoStatus(errorMsg!!, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                Text("Retry")
            }
            return
        }

        if (sessions.isEmpty()) {
            Text(
                "No sessions yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 32.dp).align(Alignment.CenterHorizontally),
            )
            return
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionCard(session = session, onClick = { onSessionClick(session.id) })
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionApi.Session,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    session.title ?: "untitled",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (session.isPersonal) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Personal",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    StatusBadge(session.status)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    session.github ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    session.created_at?.take(16)?.replace("T", " ") ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val (bg, fg) = when (status) {
        "queued" -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        "transcribing" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "summarizing" -> Color(0xFF2E7D32) to Color.White
        "needs_labeling" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "syncing" -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        "done" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        "error" -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Text(
            status.replace("_", " "),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
