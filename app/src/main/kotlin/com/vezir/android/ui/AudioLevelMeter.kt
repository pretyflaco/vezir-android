package com.vezir.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * Audio level spectrometer with graphical bars (v0.4.0).
 *
 * Two rows of [barCount] bars each (mic on top, system on bottom),
 * with a green gradient from dark (quiet) to bright (loud).  Bars are
 * drawn with gaps between them for visual separation.
 *
 * Uses the same dB-to-height mapping as the desktop
 * `audio._rms_to_bar_index()` but continuous (not quantized to 8
 * steps).  Cross-platform contract: -60 dBFS = 0%, 0 dBFS = 100%.
 *
 * Signal detection thresholds match desktop `audio.py`:
 *   - mic threshold: -60 dBFS
 *   - system threshold: -60 dBFS
 *   - silence debounce: 10 seconds (handled by CaptureService's
 *     existing playbackSilent flag)
 */
private const val BAR_COUNT = 12
private const val DB_FLOOR = -60f   // dBFS below which bar height is 0
private const val DB_CEILING = 0f   // dBFS at which bar height is 100%
private val BAR_COLOR_QUIET = Color(0xFF1B5E20)   // dark green
private val BAR_COLOR_LOUD = Color(0xFF4CAF50)    // bright green
private val BAR_COLOR_SILENT = Color(0xFF424242)  // grey for silence

/** Map dBFS [-90..0] to a 0..1 height fraction. */
private fun dbToHeight(db: Float): Float {
    if (db <= DB_FLOOR) return 0f
    if (db >= DB_CEILING) return 1f
    return (db - DB_FLOOR) / (DB_CEILING - DB_FLOOR)
}

@Composable
fun AudioLevelMeter(
    micDb: Float,
    playbackDb: Float,
    modifier: Modifier = Modifier,
) {
    // Rolling history buffers (shift left, append new).
    val micHistory = remember { FloatArray(BAR_COUNT) { -90f } }
    val sysHistory = remember { FloatArray(BAR_COUNT) { -90f } }

    // Shift and append.
    micHistory.copyInto(micHistory, 0, 1)
    micHistory[BAR_COUNT - 1] = micDb
    sysHistory.copyInto(sysHistory, 0, 1)
    sysHistory[BAR_COUNT - 1] = playbackDb

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Mic label
        Text(
            "🎤",
            style = MaterialTheme.typography.bodySmall,
        )
        // Mic bars
        LevelBars(
            history = micHistory,
            modifier = Modifier.weight(1f).height(24.dp),
        )
        // System label
        Text(
            "🔊",
            style = MaterialTheme.typography.bodySmall,
        )
        // System bars
        LevelBars(
            history = sysHistory,
            modifier = Modifier.weight(1f).height(24.dp),
        )
        // Signal indicator
        SignalIndicator(micDb = micDb, sysDb = playbackDb)
    }
}

@Composable
private fun LevelBars(
    history: FloatArray,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val barCount = history.size
        val gapFraction = 0.2f  // 20% of each slot is gap
        val slotWidth = size.width / barCount
        val barWidth = slotWidth * (1f - gapFraction)
        val gap = slotWidth * gapFraction
        val cornerR = CornerRadius(2.dp.toPx(), 2.dp.toPx())

        for (i in 0 until barCount) {
            val height = dbToHeight(history[i])
            val barHeight = max(1.dp.toPx(), height * size.height)
            val color = if (height > 0.01f) {
                lerp(BAR_COLOR_QUIET, BAR_COLOR_LOUD, height)
            } else {
                BAR_COLOR_SILENT
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(
                    x = i * slotWidth + gap / 2f,
                    y = size.height - barHeight,
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerR,
            )
        }
    }
}

@Composable
private fun SignalIndicator(
    micDb: Float,
    sysDb: Float,
) {
    val hasMic = micDb > DB_FLOOR
    val hasSys = sysDb > DB_FLOOR

    val (text, color) = when {
        hasMic && hasSys -> "✓" to Color(0xFF4CAF50)
        hasMic -> "🎤" to Color(0xFFFFA726)
        hasSys -> "🔊" to Color(0xFFFFA726)
        else -> "―" to Color(0xFF616161)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.width(20.dp),
    )
}
