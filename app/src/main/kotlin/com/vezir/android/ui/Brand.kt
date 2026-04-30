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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
 */
@Composable
fun CompactBrandHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(R.drawable.vezir_mark),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
        )
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
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
