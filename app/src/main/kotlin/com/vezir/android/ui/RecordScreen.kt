package com.vezir.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.ContextCompat
import com.vezir.android.BuildConfig
import com.vezir.android.capture.CaptureController
import com.vezir.android.capture.CaptureService
import com.vezir.android.data.Prefs

/**
 * Record screen.
 *
 * Layout pass (M5.1):
 *   - Compact brand header at top.
 *   - Optional title field.
 *   - Centered hero block: large monospace elapsed time, optional
 *     pulsing coral RecordingDot when state is RECORDING, mic + playback
 *     dBFS readouts, file path.
 *   - Single primary CTA (filled coral): Start / Stop, sized large.
 *   - Secondary actions (Import / Sign out / Share / Upload) collapsed
 *     into a discrete bottom row of TextButtons or OutlinedButtons.
 */
@Suppress("DEPRECATION")  // see menuAnchor() note below
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    prefs: Prefs,
    teamLabel: String? = null,
    teams: List<com.vezir.android.data.TeamCredential> = emptyList(),
    activeTeamId: String? = null,
    onSwitchTeam: ((String) -> Unit)? = null,
    onUpload: (
        uri: android.net.Uri,
        fileName: String,
        title: String?,
        summaryPreset: String?,
        autoLabel: Boolean,
        sync: Boolean,
    ) -> Unit,
) {
    val context = LocalContext.current
    val snapshot by CaptureController.state.collectAsState()

    var title by remember { mutableStateOf("") }
    // Preset id sent to the server with the upload.  Defaults to whatever
    // is sticky in Prefs (which itself defaults to "confidential" on a
    // fresh install — see Prefs.DEFAULT_PRESET).  Mutations are persisted
    // immediately so the next launch remembers the last-used preset.
    var preset by remember {
        mutableStateOf(prefs.summaryPreset ?: Prefs.DEFAULT_PRESET)
    }
    // Per-upload privacy toggles, both sticky across launches.
    // Defaults: auto-label ON, sync ON (matches server defaults; the
    // user opts out via Switches below the preset dropdown).
    var autoLabel by remember { mutableStateOf(prefs.autoLabel) }
    var sync by remember { mutableStateOf(prefs.sync) }
    var personal by remember { mutableStateOf(prefs.personal) }
    var presetMenuOpen by remember { mutableStateOf(false) }
    var permissionStatus by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var silenceWarningDismissed by remember { mutableStateOf(false) }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Gate ONLY on RECORD_AUDIO.  POST_NOTIFICATIONS is requested in the
        // same launcher but is optional: the foreground service runs without
        // it (the user just won't see the persistent notification).  The old
        // all-granted check refused to record when the user denied only
        // notifications — with a message that wrongly blamed RECORD_AUDIO.
        val micGranted = results[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED)
        if (micGranted) {
            permissionStatus =
                if (results[Manifest.permission.POST_NOTIFICATIONS] == false) {
                    "Heads up: notifications are off, so the recording " +
                        "status notification won't show. Recording works."
                } else null
            pendingStart = true
        } else {
            permissionStatus = "RECORD_AUDIO is required. " +
                "Grant it in Android Settings → Apps → Vezir → Permissions."
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val startIntent = CaptureService.startIntent(
                context, result.resultCode, result.data!!, title.ifBlank { null },
            )
            ContextCompat.startForegroundService(context, startIntent)
        } else {
            permissionStatus = "Recording cancelled at the consent prompt."
        }
        pendingStart = false
        silenceWarningDismissed = false  // reset on new recording
    }

    if (pendingStart) {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val intent = mpm.createScreenCaptureIntent()
        SideEffectOnce(pendingStart) {
            mediaProjectionLauncher.launch(intent)
        }
    }

    val recording = snapshot.state == CaptureController.State.RECORDING
    val paused = snapshot.state == CaptureController.State.PAUSED
    val active = recording || paused  // capture thread is alive
    val starting = snapshot.state == CaptureController.State.STARTING
    val stopping = snapshot.state == CaptureController.State.STOPPING
    val idleish = snapshot.state == CaptureController.State.IDLE ||
        snapshot.state == CaptureController.State.FINISHED ||
        snapshot.state == CaptureController.State.ERROR

    ScreenScaffold {
        CompactBrandHeader(
            title = "record",
            teamLabel = teamLabel,
            teams = teams,
            activeTeamId = activeTeamId,
            onSwitchTeam = onSwitchTeam,
        )

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Meeting title (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            enabled = idleish,
        )

        // Summarization preset dropdown.  Choice is persisted to Prefs so
        // it sticks across launches; default on first install is the
        // Confidential (Tinfoil TEE) backend per project policy.
        ExposedDropdownMenuBox(
            expanded = presetMenuOpen,
            onExpandedChange = { if (idleish) presetMenuOpen = !presetMenuOpen },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = presetLabelFor(preset),
                onValueChange = { /* read-only */ },
                readOnly = true,
                singleLine = true,
                enabled = idleish,
                label = { Text("Summarization preset") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetMenuOpen)
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                // NOTE: .menuAnchor() is deprecated in Material3 1.4+ in
                // favor of an overload that takes ExposedDropdownMenuAnchorType
                // + enabled.  We're on 1.3.x (Compose BOM 2024.11) which
                // doesn't expose the new overload yet; suppress at the
                // function level until the next BOM bump.
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = presetMenuOpen,
                onDismissRequest = { presetMenuOpen = false },
            ) {
                PRESET_OPTIONS.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            preset = id
                            prefs.summaryPreset = id
                            presetMenuOpen = false
                        },
                    )
                }
            }
        }

        // Per-upload privacy toggles.  Both default ON; flipping persists
        // immediately to EncryptedSharedPreferences so the next launch
        // remembers the choice.  Disabled while not idle so a recording
        // in flight always uses the toggle state captured at start.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Auto-label speakers",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = autoLabel,
                onCheckedChange = {
                    autoLabel = it
                    prefs.autoLabel = it
                },
                enabled = idleish,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Personal recording",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = personal,
                onCheckedChange = {
                    personal = it
                    prefs.personal = it
                },
                enabled = idleish,
            )
        }

        // Hero status block.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (recording) RecordingDot(sizeDp = 14)
                Text(
                    formatHmsMillis(if (active) snapshot.recordingMs else snapshot.elapsedMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                )
                if (paused) {
                    Text(
                        "PAUSED",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            MonoStatus(
                "state ${snapshot.state.name.lowercase()}  " +
                    "size ${formatSize(snapshot.bytesWritten)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // v0.4.0: audio level spectrometer replaces numeric dBFS text.
            val levels by CaptureController.levels.collectAsState()
            AudioLevelMeter(
                micDb = levels.micDb,
                playbackDb = levels.playbackDb,
            )
            if (snapshot.displayPath != null) {
                MonoStatus(
                    snapshot.displayPath!!,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (snapshot.playbackSilent && active && !silenceWarningDismissed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "No direct playback capture — audio may still be captured via mic",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { silenceWarningDismissed = true },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (snapshot.errorMessage != null) {
            Text(
                "Error: ${snapshot.errorMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Primary CTA.
        when {
            idleish -> {
                Button(
                    onClick = { startFlow(context, recordAudioLauncher::launch) {
                        pendingStart = true
                    } },
                    enabled = !pendingStart,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(
                        if (snapshot.state == CaptureController.State.FINISHED)
                            "Start another recording"
                        else "Start recording"
                    )
                }
            }
            starting -> Button(
                onClick = {}, enabled = false,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Starting…") }
            active -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            context.startService(
                                Intent(context, CaptureService::class.java).apply {
                                    action = CaptureService.ACTION_TOGGLE_PAUSE
                                },
                            )
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text(if (paused) "Resume" else "Pause") }

                    Button(
                        onClick = { context.startService(CaptureService.stopIntent(context)) },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text("Stop") }
                }
            }
            stopping -> Button(
                onClick = {}, enabled = false,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Stopping…") }
            else -> {}
        }

        // Finished-state actions.
        if (snapshot.state == CaptureController.State.FINISHED) {
            val finishedUri = snapshot.outputUri
            val finishedName = snapshot.displayName ?: "vezir.ogg"
            val finishedTitle = title.ifBlank { null }

            Button(
                onClick = {
                    if (finishedUri != null) {
                        onUpload(
                            finishedUri, finishedName, finishedTitle,
                            preset, autoLabel, sync,
                        )
                    }
                },
                enabled = finishedUri != null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Upload to vezir") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (finishedUri != null) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/ogg"
                                putExtra(Intent.EXTRA_STREAM, finishedUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(send, "Share recording"),
                            )
                        }
                    },
                    enabled = finishedUri != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
                OutlinedButton(
                    onClick = { CaptureController.acknowledgeFinished() },
                    modifier = Modifier.weight(1f),
                ) { Text("Dismiss") }
            }
        }

        if (permissionStatus != null) {
            MonoStatus(permissionStatus!!,
                color = MaterialTheme.colorScheme.error)
        }

        Text(
            "Max recording duration: %.1f h (hard stop)"
                .format(BuildConfig.MAX_RECORDING_MILLIS / 3_600_000.0),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Decide whether we still need to ask for RECORD_AUDIO + POST_NOTIFICATIONS
 * before kicking the MediaProjection consent prompt. If permissions are
 * already granted, [andThen] is invoked synchronously.
 */
private fun startFlow(
    context: Context,
    launchPermissions: (Array<String>) -> Unit,
    andThen: () -> Unit,
) {
    val needed = mutableListOf<String>()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED) {
        needed += Manifest.permission.RECORD_AUDIO
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
    }
    if (needed.isEmpty()) andThen()
    else launchPermissions(needed.toTypedArray())
}

private val PRESET_OPTIONS get() = Prefs.PRESET_OPTIONS

private fun presetLabelFor(id: String): String = Prefs.presetLabelFor(id)

private fun formatHmsMillis(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}

private fun formatDb(db: Float): String =
    if (db <= -89f) " --" else "%5.1f".format(db)

@Composable
private fun SideEffectOnce(key: Any, block: () -> Unit) {
    val triggered = remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(key) {
        if (key == true && !triggered.value) {
            triggered.value = true
            block()
        } else if (key == false) {
            triggered.value = false
        }
    }
}
