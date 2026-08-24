package com.vezir.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vezir.android.data.Prefs
import com.vezir.android.net.AudioClipPlayer
import com.vezir.android.net.LabelApi
import com.vezir.android.net.ResilientApi
import kotlinx.coroutines.launch

/**
 * Native speaker labeling screen.
 *
 * Fetches the speaker list from the server, lets the user listen to
 * audio clips and assign names (with team-handle autocomplete), then
 * submits the label map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelScreen(
    prefs: Prefs,
    sessionId: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val cred = remember(prefs.activeTeamId) { prefs.activeCredential() }

    if (cred == null) {
        onCancel()
        return
    }

    // v0.4.4: resolve reachable URL (nvpn/Tailscale failover).
    var resolvedUrl by remember { mutableStateOf(cred.url) }
    LaunchedEffect(cred) {
        val resilient = ResilientApi(
            cred.url, cred.altUrls, cred.token, cred.id, cred.caPem,
        )
        resolvedUrl = resilient.findReachableUrl() ?: cred.url
    }

    val api = remember(resolvedUrl) {
        LabelApi(resolvedUrl, cred.token, cred.id, cred.caPem)
    }
    val clipPlayer = remember(cred) {
        AudioClipPlayer(cred.token, cred.id, cred.caPem, context.cacheDir)
    }

    // Cleanup player on dispose.
    DisposableEffect(Unit) { onDispose { clipPlayer.release() } }

    // State.
    var loading by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var labelData by remember { mutableStateOf<LabelApi.LabelData?>(null) }
    val labelValues = remember { mutableStateMapOf<String, String>() }
    var playingSpeaker by remember { mutableStateOf<String?>(null) }
    var clipError by remember { mutableStateOf<String?>(null) }
    // Segments dialog (v0.11.1): null = closed; loading/content per speaker.
    var segmentsSpeaker by remember { mutableStateOf<String?>(null) }
    var segmentsData by remember { mutableStateOf<LabelApi.SegmentsData?>(null) }
    var segmentsError by remember { mutableStateOf<String?>(null) }

    // Fetch speakers on first composition.
    LaunchedEffect(sessionId) {
        loading = true
        errorMsg = null
        when (val result = api.getSpeakers(sessionId)) {
            is LabelApi.Result.Ok -> {
                labelData = result.data
                // Pre-fill empty labels for each speaker.
                for (sp in result.data.speakers) {
                    labelValues.putIfAbsent(sp.id, "")
                }
            }
            is LabelApi.Result.HttpError ->
                errorMsg = "Server error: ${result.code} ${result.message}"
            is LabelApi.Result.NetworkError ->
                errorMsg = "Network error: ${result.cause.message}"
        }
        loading = false
    }

    // Custom layout instead of ScreenScaffold: we need a LazyColumn for
    // the speaker list, which cannot be nested inside ScreenScaffold's
    // verticalScroll Column (nested scrollable containers crash).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompactBrandHeader(title = "label speakers")

            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (errorMsg != null) {
                MonoStatus(errorMsg!!, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
                return@Column
            }

            val data = labelData ?: return@Column

            Text(
                "session ${data.session_id}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!data.audio_available) {
                MonoStatus(
                    "audio deleted — listen to clips on the web dashboard if needed",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (clipError != null) {
                MonoStatus(clipError!!, color = MaterialTheme.colorScheme.error)
            }

            // Speaker list — LazyColumn gets remaining vertical space.
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(data.speakers, key = { it.id }) { speaker ->
                    SpeakerCard(
                        speaker = speaker,
                        label = labelValues[speaker.id] ?: "",
                        onLabelChange = { labelValues[speaker.id] = it },
                        team = data.team,
                        audioAvailable = data.audio_available,
                        isPlaying = playingSpeaker == speaker.id,
                        onPlay = {
                            scope.launch {
                                clipError = null
                                clipPlayer.play(
                                    url = api.clipUrl(sessionId, speaker.id),
                                    speakerId = speaker.id,
                                    onComplete = { playingSpeaker = null },
                                    onError = { msg ->
                                        clipError = "${speaker.id}: $msg"
                                        playingSpeaker = null
                                    },
                                )
                                playingSpeaker = speaker.id
                            }
                        },
                        onStop = {
                            clipPlayer.stop()
                            playingSpeaker = null
                        },
                        onMore = {
                            segmentsSpeaker = speaker.id
                            segmentsData = null
                            segmentsError = null
                            scope.launch {
                                when (val r = api.getSegments(sessionId, speaker.id)) {
                                    is LabelApi.Result.Ok -> segmentsData = r.data
                                    is LabelApi.Result.HttpError ->
                                        segmentsError = "${r.code} ${r.message}"
                                    is LabelApi.Result.NetworkError ->
                                        segmentsError = "Network error: ${r.cause.message}"
                                }
                            }
                        },
                    )
                }
            }

            // Submit.
            if (submitting) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        submitting = true
                        clipPlayer.stop()
                        val nonEmpty = labelValues.filter { it.value.isNotBlank() }
                        when (val result = api.submitLabels(sessionId, nonEmpty)) {
                            is LabelApi.Result.Ok -> {
                                submitting = false
                                onDone()
                            }
                            is LabelApi.Result.HttpError -> {
                                errorMsg = "Submit failed: ${result.code} ${result.message}"
                                submitting = false
                            }
                            is LabelApi.Result.NetworkError -> {
                                errorMsg = "Network error: ${result.cause.message}"
                                submitting = false
                            }
                        }
                    }
                },
                enabled = !submitting && labelValues.values.any { it.isNotBlank() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Submit labels") }

            OutlinedButton(
                onClick = {
                    clipPlayer.stop()
                    onCancel()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }

        // Segments dialog (v0.11.1): everything this speaker said.
        segmentsSpeaker?.let { spId ->
            AlertDialog(
                onDismissRequest = { segmentsSpeaker = null },
                title = { Text("$spId — all segments") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        when {
                            segmentsError != null -> Text(
                                "Could not load segments: ${segmentsError!!}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            segmentsData == null -> CircularProgressIndicator()
                            else -> {
                                val d = segmentsData!!
                                d.segments.forEach { seg ->
                                    Text(
                                        "[${fmtMmSs(seg.start)}]  ${seg.text}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (d.total > d.segments.size) {
                                    Text(
                                        "… (${d.total - d.segments.size} more not shown)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { segmentsSpeaker = null }) { Text("Close") }
                },
            )
        }
    }
}

private fun fmtMmSs(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerCard(
    speaker: LabelApi.Speaker,
    label: String,
    onLabelChange: (String) -> Unit,
    team: List<String>,
    audioAvailable: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onMore: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    speaker.id,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // "More": show every segment this speaker said —
                    // the single sample line is often not enough to
                    // identify them (v0.11.1, needs server >= 0.15.0).
                    TextButton(onClick = onMore) { Text("More") }
                    if (audioAvailable) {
                        IconButton(onClick = { if (isPlaying) onStop() else onPlay() }) {
                            Icon(
                                if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Stop" else "Play clip",
                            )
                        }
                    }
                }
            }

            if (!speaker.sample_text.isNullOrBlank()) {
                Text(
                    "\"${speaker.sample_text}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }

            // Name input with team-handle autocomplete.
            var expanded by remember { mutableStateOf(false) }
            val filtered = remember(label, team) {
                if (label.isBlank()) team
                else team.filter { it.contains(label, ignoreCase = true) }
            }

            ExposedDropdownMenuBox(
                expanded = expanded && filtered.isNotEmpty(),
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        onLabelChange(it)
                        expanded = true
                    },
                    label = { Text("Name") },
                    placeholder = { Text("github handle or name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                )
                ExposedDropdownMenu(
                    expanded = expanded && filtered.isNotEmpty(),
                    onDismissRequest = { expanded = false },
                ) {
                    filtered.forEach { handle ->
                        DropdownMenuItem(
                            text = { Text(handle) },
                            onClick = {
                                onLabelChange(handle)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}
