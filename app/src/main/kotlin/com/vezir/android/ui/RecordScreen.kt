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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * v1 record screen.
 *
 * Flow:
 *   1. Compose form for an optional title.
 *   2. Tap "Start recording" → request RECORD_AUDIO + POST_NOTIFICATIONS,
 *      then launch the MediaProjection consent prompt.
 *   3. On consent, start [CaptureService] which owns the capture pipeline.
 *   4. Live elapsed/RMS/file-size from [CaptureController.state].
 *   5. Tap "Stop recording" → service drains, finalizes the OGG, returns
 *      to FINISHED state.
 *   6. M3 will wire the FINISHED state to upload + dashboard handoff.
 */
@Composable
fun RecordScreen(
    prefs: Prefs,
    onSignOut: () -> Unit,
    onUpload: (uri: android.net.Uri, fileName: String, title: String?) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot by CaptureController.state.collectAsState()

    var title by remember { mutableStateOf("") }
    var permissionStatus by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }

    // ── Runtime permission requesters ──
    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            permissionStatus = null
            // Continue: ask for MediaProjection consent.
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
            // Launch the foreground capture service.
            val startIntent = CaptureService.startIntent(
                context, result.resultCode, result.data!!, title.ifBlank { null },
            )
            ContextCompat.startForegroundService(context, startIntent)
        } else {
            permissionStatus = "Recording cancelled at the consent prompt."
        }
        pendingStart = false
    }

    // When permissions are granted, kick the projection consent prompt.
    if (pendingStart) {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val intent = mpm.createScreenCaptureIntent()
        // LaunchedEffect-equivalent guard: only fire once per pendingStart=true
        SideEffectOnce(pendingStart) {
            mediaProjectionLauncher.launch(intent)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vezir", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Record a meeting on this device. Audio is encoded to OGG/Opus " +
                "on-device and saved locally; upload lands in the next milestone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Server: ${prefs.serverUrl ?: "(unset)"}",
            style = MaterialTheme.typography.bodySmall)
        val maxHours = BuildConfig.MAX_RECORDING_MILLIS / 3_600_000.0
        Text("Max recording duration: %.1f h (hard stop)".format(maxHours),
            style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Meeting title (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            enabled = snapshot.state == CaptureController.State.IDLE ||
                snapshot.state == CaptureController.State.FINISHED ||
                snapshot.state == CaptureController.State.ERROR,
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        // ── Live status ──
        StatusBlock(snapshot)

        // ── Action buttons ──
        when (snapshot.state) {
            CaptureController.State.IDLE,
            CaptureController.State.FINISHED,
            CaptureController.State.ERROR -> {
                Button(
                    onClick = { startFlow(context, recordAudioLauncher::launch) {
                        // permissions already granted; kick consent directly
                        pendingStart = true
                    } },
                    enabled = !pendingStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (snapshot.state == CaptureController.State.FINISHED)
                            "Start another recording"
                        else "Start recording"
                    )
                }
            }
            CaptureController.State.STARTING -> {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Starting…")
                }
            }
            CaptureController.State.RECORDING -> {
                Button(
                    onClick = {
                        context.startService(CaptureService.stopIntent(context))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop recording") }
            }
            CaptureController.State.STOPPING -> {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Stopping…")
                }
            }
        }

        if (snapshot.state == CaptureController.State.FINISHED) {
            Text(
                "Recording saved to ${snapshot.displayPath ?: "(unknown path)"}.",
                style = MaterialTheme.typography.bodySmall,
            )
            val finishedUri = snapshot.outputUri
            val finishedName = snapshot.displayName ?: "vezir.ogg"
            val finishedTitle = title.ifBlank { null }
            Button(
                onClick = {
                    if (finishedUri != null) {
                        onUpload(finishedUri, finishedName, finishedTitle)
                    }
                },
                enabled = finishedUri != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Upload to vezir") }
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
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share (manual)") }
            OutlinedButton(
                onClick = { CaptureController.acknowledgeFinished() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Dismiss") }
        }

        OutlinedButton(
            onClick = onImport,
            enabled = snapshot.state == CaptureController.State.IDLE ||
                snapshot.state == CaptureController.State.FINISHED ||
                snapshot.state == CaptureController.State.ERROR,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import existing recording")
        }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out (clear token)")
        }

        if (permissionStatus != null) {
            Text(permissionStatus!!,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun StatusBlock(snapshot: CaptureController.Snapshot) {
    val elapsed = formatHmsMillis(snapshot.elapsedMs)
    val sizeKib = snapshot.bytesWritten / 1024.0
    val pdb = snapshot.playbackRmsDbfs
    val mdb = snapshot.micRmsDbfs

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "State: ${snapshot.state.name}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Elapsed: $elapsed",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Size: %.1f KiB".format(sizeKib),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Levels: playback %s dBFS  mic %s dBFS".format(
                if (pdb <= -89f) " --" else "%5.1f".format(pdb),
                if (mdb <= -89f) " --" else "%5.1f".format(mdb),
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (snapshot.displayPath != null) {
            Text(
                "File: ${snapshot.displayPath}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (snapshot.playbackSilent && snapshot.state == CaptureController.State.RECORDING) {
            Text(
                "Playback capture appears silent. " +
                    "If your meeting app routes audio through the call/voice " +
                    "channel (e.g. Signal), Android may block playback capture. " +
                    "We are still recording the microphone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (snapshot.errorMessage != null) {
            Text(
                "Error: ${snapshot.errorMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
    if (needed.isEmpty()) {
        andThen()
    } else {
        launchPermissions(needed.toTypedArray())
    }
}

private fun formatHmsMillis(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * Like [androidx.compose.runtime.LaunchedEffect] but fires `block` exactly
 * once per `key` becoming true (compared to false). Keeps the
 * MediaProjection prompt from being relaunched on recomposition.
 */
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
