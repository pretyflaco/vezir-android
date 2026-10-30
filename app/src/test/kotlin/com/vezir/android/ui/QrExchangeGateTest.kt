package com.vezir.android.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrExchangeGateTest {
    @Test
    fun failedAttemptStaysLatchedAcrossRepeatedScans() {
        val gate = QrExchangeGate()
        assertTrue(gate.startScannedAttempt())
        gate.attemptFailed()

        assertFalse(gate.startScannedAttempt())
        assertFalse(gate.startScannedAttempt())
        assertFalse(gate.startScannedAttempt())
    }

    @Test
    fun explicitRetryAllowsExactlyOneNewAttempt() {
        val gate = QrExchangeGate()
        assertTrue(gate.startScannedAttempt())
        gate.attemptFailed()

        assertTrue(gate.startRetry())
        assertFalse(gate.startRetry())
        assertFalse(gate.startScannedAttempt())

        gate.attemptFailed()
        assertFalse(gate.startScannedAttempt())
        assertTrue(gate.startRetry())
    }

    @Test
    fun successfulAttemptDoesNotEnableRetryOrAnotherScan() {
        val gate = QrExchangeGate()
        assertTrue(gate.startScannedAttempt())
        gate.attemptSucceeded()

        assertFalse(gate.startRetry())
        assertFalse(gate.startScannedAttempt())
    }
}
