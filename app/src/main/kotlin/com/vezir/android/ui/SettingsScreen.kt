package com.vezir.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vezir.android.BuildConfig
import com.vezir.android.data.Prefs
import com.vezir.android.data.TeamCredentialStore

/**
 * Settings screen — third tab in the bottom nav.
 *
 * Sections:
 *   1. Teams: active team display, list of enrolled teams, add/remove/switch.
 *   2. Recording defaults: sync-to-git, auto-delete-after-upload toggles
 *      (moved here from RecordScreen; these are install-wide defaults).
 *   3. Tools: import recording, sign out.
 *   4. About: app name + version.
 */
@Composable
fun SettingsScreen(
    prefs: Prefs,
    teamStore: TeamCredentialStore,
    activeTeamLabel: String?,
    onImport: () -> Unit,
    onSignOut: () -> Unit,
    onAddTeam: () -> Unit,
    onSwitchTeam: (teamId: String) -> Unit,
) {
    var sync by remember { mutableStateOf(prefs.sync) }
    var autoDelete by remember { mutableStateOf(prefs.autoDeleteAfterUpload) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showRemoveTeamDialog by remember { mutableStateOf<String?>(null) }

    val teams = remember(prefs.teamsJson) { teamStore.loadAll() }
    val activeId = teamStore.activeId()

    ScreenScaffold {
        CompactBrandHeader(title = "settings")

        // ── Section: Teams ──
        Text(
            "Teams",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        if (teams.isNotEmpty()) {
            teams.forEach { team ->
                val isActive = team.id == activeId
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            team.label.ifBlank { team.id },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        )
                        if (team.github != null) {
                            Text(
                                "@${team.github}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isActive) {
                            Text(
                                "Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (!isActive) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onSwitchTeam(team.id) },
                            ) { Text("Use") }
                            TextButton(
                                onClick = { showRemoveTeamDialog = team.id },
                            ) { Text("Remove") }
                        }
                    }
                }
            }
        } else if (activeTeamLabel != null) {
            // Legacy mode — show current team from /api/me
            Text(
                activeTeamLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        OutlinedButton(
            onClick = onAddTeam,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add another team") }

        HorizontalDivider()

        // ── Section: Recording defaults ──
        Text(
            "Recording defaults",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Sync to git",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = sync,
                onCheckedChange = {
                    sync = it
                    prefs.sync = it
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Auto-delete after upload",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = autoDelete,
                onCheckedChange = {
                    autoDelete = it
                    prefs.autoDeleteAfterUpload = it
                },
            )
        }

        HorizontalDivider()

        // ── Section: Tools ──
        Text(
            "Tools",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        OutlinedButton(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Import recording") }

        OutlinedButton(
            onClick = { showSignOutDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Sign out",
                color = MaterialTheme.colorScheme.error,
            )
        }

        HorizontalDivider()

        // ── Section: About ──
        Text(
            "About",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Vezir",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
    }

    // ── Dialogs ──

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign out") },
            text = {
                Text("This will clear all credentials and enrolled teams from this device. You will need to re-enroll via QR code to use the app again.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    onSignOut()
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            },
        )
    }

    showRemoveTeamDialog?.let { teamId ->
        val teamLabel = teams.firstOrNull { it.id == teamId }?.label ?: teamId
        AlertDialog(
            onDismissRequest = { showRemoveTeamDialog = null },
            title = { Text("Remove team") },
            text = {
                Text("Remove \"$teamLabel\" from this device? You can re-add it later by scanning a QR code.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveTeamDialog = null
                    teamStore.remove(teamId)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveTeamDialog = null }) { Text("Cancel") }
            },
        )
    }
}
