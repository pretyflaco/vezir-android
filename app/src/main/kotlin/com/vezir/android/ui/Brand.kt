package com.vezir.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vezir.android.R
import com.vezir.android.ui.theme.VezirCoral

/**
 * Shared building blocks for the v1 layout pass.
 *
 *  - [BrandHeader] / [CompactBrandHeader] put the vezir lockup at the top
 *    of each screen with consistent breathing room.
 *  - [ScreenScaffold] wraps screen content in a centered, max-480dp
 *    column so the action surface doesn't stretch on tablets / landscape
 *    and sits in a "letter-pocket" rather than smeared across the top
 *    of a 19.5:9 phone.
 *  - [RecordingDot] is the small coral pulsing audio-dot used during
 *    the RECORDING state.
 */

/**
 * Tall brand header with the full mark + wordmark lockup. Use on the
 * primary entry screen (Setup) so the brand is unambiguous.
 */
@Composable
fun BrandHeader(
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.vezir_logo),
            contentDescription = "vezir",
            modifier = Modifier.height(56.dp),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Compact brand header with the mark only. Used on Record / Upload /
 * Import where the action surface needs the screen real estate.
 *
 * When [teamLabel] is non-null and there are multiple teams, a
 * dropdown button is rendered right-aligned in the same row, allowing
 * one-tap team switching without a separate TopAppBar.
 */
@Composable
fun CompactBrandHeader(
    title: String,
    modifier: Modifier = Modifier,
    teamLabel: String? = null,
    teams: List<com.vezir.android.data.TeamCredential> = emptyList(),
    activeTeamId: String? = null,
    onSwitchTeam: ((String) -> Unit)? = null,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.vezir_mark),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        if (teamLabel != null && teams.size > 1 && onSwitchTeam != null) {
            Box {
                TextButton(onClick = { dropdownExpanded = true }) {
                    Text(
                        teamLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "Switch team",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    teams.forEach { team ->
                        val isActive = team.id == activeTeamId
                        DropdownMenuItem(
                            text = {
                                Text(
                                    team.label.ifBlank { team.id },
                                    color = if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                dropdownExpanded = false
                                onSwitchTeam(team.id)
                            },
                        )
                    }
                }
            }
        } else if (teamLabel != null) {
            // Single team — show label without dropdown
            Text(
                teamLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Wraps screen content in a centered, max-480dp column so phone
 * portrait keeps a comfortable column width and tablets / landscape
 * don't spread inputs and buttons all the way across the screen.
 */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // Consume safe-drawing insets so MainActivity's enableEdgeToEdge()
            // doesn't draw screen content under the Android status bar
            // (top) or gesture-nav bar (bottom).  Without this, the
            // CompactBrandHeader logo on Record overlaps the system clock
            // / battery icons.  Applied at ScreenScaffold level so every
            // screen inherits the same safe area.
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .then(
                    if (scrollable) Modifier.verticalScroll(rememberScrollState())
                    else Modifier,
                )
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * Subtly pulsing coral dot. Renders the brand "audio dot" as a UI
 * affordance for the RECORDING state.
 */
@Composable
fun RecordingDot(
    sizeDp: Int = 12,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "recording-dot")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording-dot-alpha",
    )
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(VezirCoral),
    )
}

/**
 * Status pill rendered in monospace to differentiate technical status
 * from prose. Used by RecordScreen and UploadScreen for state lines.
 */
@Composable
fun MonoStatus(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
    )
}
