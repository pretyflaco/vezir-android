package com.vezir.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vezir.android.BuildConfig
import com.vezir.android.data.Prefs

/**
 * Stub for M2. Renders the configured server URL so the user can confirm
 * enrollment worked, plus a "Sign out" affordance that clears prefs.
 *
 * The capture pipeline (MediaProjection consent flow, dual AudioRecord,
 * resample/mix, MediaCodec Opus, Ogg writer, 3h hard cap) lands in M2.
 */
@Composable
fun RecordScreen(
    prefs: Prefs,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vezir", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Configured. The recorder will land in the next milestone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Server: ${prefs.serverUrl ?: "(unset)"}",
            style = MaterialTheme.typography.bodySmall)

        val maxHours = BuildConfig.MAX_RECORDING_MILLIS / 3_600_000.0
        Text("Max recording duration (build): %.1f h".format(maxHours),
            style = MaterialTheme.typography.bodySmall)

        Button(onClick = { /* M2: open MediaProjection consent + start capture */ },
            enabled = false) {
            Text("Start recording (M2)")
        }
        OutlinedButton(onClick = { /* M4: SAF picker for an existing recording */ },
            enabled = false) {
            Text("Import existing recording (M4)")
        }
        OutlinedButton(onClick = onSignOut) {
            Text("Sign out (clear token)")
        }
    }
}
