package com.vezir.android.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FtypMagicTest {

    private fun mp4Header(): ByteArray =
        byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(),
            'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(16)

    @Test
    fun `ftyp box is detected`() {
        assertTrue(looksLikeFtyp(mp4Header()))
    }

    @Test
    fun `quicktime ftyp brand is detected`() {
        val qt = byteArrayOf(0, 0, 0, 0x14, 'f'.code.toByte(), 't'.code.toByte(),
            'y'.code.toByte(), 'p'.code.toByte(), 'q'.code.toByte(), 't'.code.toByte())
        assertTrue(looksLikeFtyp(qt))
    }

    @Test
    fun `short input is rejected`() {
        assertFalse(looksLikeFtyp(byteArrayOf(0, 0, 0, 4)))
        assertFalse(looksLikeFtyp(ByteArray(0)))
    }

    @Test
    fun `ogg is not ftyp`() {
        val ogg = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(),
            'g'.code.toByte(), 'S'.code.toByte()) + ByteArray(8)
        assertFalse(looksLikeFtyp(ogg))
        assertTrue(looksLikeOggs(ogg))
    }

    @Test
    fun `mp4 is not oggs`() {
        assertFalse(looksLikeOggs(mp4Header()))
    }
}
