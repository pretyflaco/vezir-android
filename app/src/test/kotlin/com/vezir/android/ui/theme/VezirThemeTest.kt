package com.vezir.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Brand tokens must match the upstream brand book exactly:
 * pretyflaco/vezir/assets/logo/README.md.
 *
 * If someone re-tints these values, the test fails so we notice before
 * shipping. Three colors. No gradients. No fourth color.
 */
class VezirThemeTest {

    @Test
    fun inkIsBrandHex() {
        assertEquals(0xFF111111.toInt(), VezirInk.toArgbInt())
    }

    @Test
    fun surfaceIsBrandHex() {
        assertEquals(0xFFFFFFFF.toInt(), VezirSurface.toArgbInt())
    }

    @Test
    fun coralIsBrandHex() {
        assertEquals(0xFFFF6B35.toInt(), VezirCoral.toArgbInt())
    }
}

/**
 * androidx.compose.ui.graphics.Color stores its components as packed
 * floats; convert to a plain ARGB Int the way `toArgb()` does, but
 * without depending on Android's runtime (which would force this test
 * onto an emulator). Unit test stays pure JVM.
 */
private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
