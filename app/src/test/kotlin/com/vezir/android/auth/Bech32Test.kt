package com.vezir.android.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Bech32Test {

    @Test
    fun npubToHex_knownVector() {
        // El Flaco's npub <-> hex (from the operator allowlist).
        val npub = "npub1flac02t5hw6jljk8x7mec22uq37ert8d3y3mpwzcma726g5pz4lsmfzlk6"
        val hex = "4ffb87a974bbb52fcac737b79c295c047d91aced8923b0b858df7cad2281157f"
        assertEquals(hex, Bech32.npubToHex(npub))
    }

    @Test
    fun npubToHex_rejectsGarbage() {
        assertNull(Bech32.npubToHex("npub1notvalid"))
        assertNull(Bech32.npubToHex("nsec1qqqqqqqq"))   // wrong hrp
        assertNull(Bech32.npubToHex("definitely not bech32"))
    }
}
