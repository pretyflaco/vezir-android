package com.vezir.android.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EosGuardTest {

    @Test
    fun `action runs exactly once across repeated signals`() {
        var calls = 0
        val guard = EosGuard()
        repeat(5) { guard.signalOnce { calls++ } }
        assertEquals(1, calls)
        assertTrue(guard.isSignaled)
    }

    @Test
    fun `a throwing action does not propagate and is not retried`() {
        var calls = 0
        val guard = EosGuard()
        guard.signalOnce { calls++; error("IllegalStateException: invalid state") }
        guard.signalOnce { calls++ }
        assertEquals(1, calls)
        assertTrue(guard.isSignaled)
    }

    @Test
    fun `guard starts unsignaled`() {
        assertFalse(EosGuard().isSignaled)
    }
}
