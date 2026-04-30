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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            permissionStatus = null
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
    }

    if (pendingStart) {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val intent = mpm.createScreenCaptureIntent()
        SideEffectOnce(pendingStart) {
            mediaProjectionLauncher.launch(intent)
        }
    }

    val recording = snapshot.state == CaptureController.State.RECORDING
    val starting = snapshot.state == CaptureController.State.STARTING
    val stopping = snapshot.state == CaptureController.State.STOPPING
    val idleish = snapshot.state == CaptureController.State.IDLE ||
        snapshot.state == CaptureController.State.FINISHED ||
        snapshot.state == CaptureController.State.ERROR

    ScreenScaffold {
        CompactBrandHeader(title = "record")

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Meeting title (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
            enabled = idleish,
        )

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
                    formatHmsMillis(snapshot.elapsedMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            MonoStatus(
                "state ${snapshot.state.name.lowercase()}  " +
                    "size ${formatSize(snapshot.bytesWritten)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MonoStatus(
                "play ${formatDb(snapshot.playbackRmsDbfs)}  " +
                    "mic  ${formatDb(snapshot.micRmsDbfs)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.displayPath != null) {
                MonoStatus(
                    snapshot.displayPath!!,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (snapshot.playbackSilent && recording) {
            Text(
                "Playback capture appears silent. If your meeting app routes " +
                    "audio through the call/voice channel (e.g. Signal), " +
                    "Android may block playback capture. We are still " +
                    "recording the microphone.",
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
            recording -> Button(
                onClick = { context.startService(CaptureService.stopIntent(context)) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Stop recording") }
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
                        onUpload(finishedUri, finishedName, finishedTitle)
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

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        // Secondary affordances row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onImport,
                enabled = idleish,
                modifier = Modifier.weight(1f),
            ) { Text("Import recording") }
            TextButton(
                onClick = onSignOut,
                modifier = Modifier.weight(1f),
            ) { Text("Sign out") }
        }

        Text(
            "Max recording duration: %.1f h (hard stop)"
                .format(BuildConfig.MAX_RECORDING_MILLIS / 3_600_000.0),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (permissionStatus != null) {
            MonoStatus(permissionStatus!!,
                color = MaterialTheme.colorScheme.error)
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
    if (needed.isEmpty()) andThen()
    else launchPermissions(needed.toTypedArray())
}

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
