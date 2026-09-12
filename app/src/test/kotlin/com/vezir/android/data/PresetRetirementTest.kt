package com.vezir.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preset axis is retired (server 0.20.0 / millet 0.19.0): every summary
 * backend is private, so only the default is offered.  The legacy ids are
 * still accepted server-side until 0.22.0, so anything that *stored* one
 * must keep rendering and must not leave a picker with no selection.
 */
class PresetRetirementTest {

    @Test
    fun `only the default preset is offered`() {
        assertEquals(1, Prefs.PRESET_OPTIONS.size)
        assertEquals(Prefs.DEFAULT_PRESET, Prefs.PRESET_OPTIONS.first().first)
    }

    @Test
    fun `retired ids still render a human label`() {
        // A session summarized before the retirement still shows its preset.
        assertTrue(Prefs.presetLabelFor("high-quality").isNotBlank())
        assertTrue(Prefs.presetLabelFor("alternative").isNotBlank())
        assertEquals("High Quality (retired)", Prefs.presetLabelFor("high-quality"))
    }

    @Test
    fun `unknown id falls back to the id itself rather than blank`() {
        assertEquals("something-else", Prefs.presetLabelFor("something-else"))
    }

    @Test
    fun `retired stored preset is coerced to an offered one`() {
        // Otherwise the radio group / dropdown would have no selection.
        assertEquals(Prefs.DEFAULT_PRESET, Prefs.offeredPresetOr("high-quality"))
        assertEquals(Prefs.DEFAULT_PRESET, Prefs.offeredPresetOr("alternative"))
        assertEquals(Prefs.DEFAULT_PRESET, Prefs.offeredPresetOr(null))
        assertEquals(Prefs.DEFAULT_PRESET, Prefs.offeredPresetOr("garbage"))
    }

    @Test
    fun `an offered preset is passed through unchanged`() {
        assertEquals("confidential", Prefs.offeredPresetOr("confidential"))
    }
}
