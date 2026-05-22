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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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

    val api = remember {
        LabelApi(prefs.serverUrl!!, prefs.token!!, prefs.caPem)
    }
    val clipPlayer = remember {
        AudioClipPlayer(prefs.token!!, prefs.caPem, context.cacheDir)
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
    }
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
                if (audioAvailable) {
                    IconButton(onClick = { if (isPlaying) onStop() else onPlay() }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Stop" else "Play clip",
                        )
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
