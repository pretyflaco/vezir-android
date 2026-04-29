package com.vezir.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Validates the pure-Kotlin Ogg page writer:
 *   1. Headers parse as RFC 7845 OpusHead / OpusTags.
 *   2. Page CRCs verify (RFC 3533 polynomial 0x04C11DB7, init 0).
 *   3. Granule positions match input.
 *   4. EOS flag is set on the final page only.
 *
 * No Android dependencies; runs as a JVM unit test.
 */
class OggOpusWriterTest {

    @Test
    fun emitsValidOpusHeadAndTags() {
        val baos = ByteArrayOutputStream()
        OggOpusWriter(baos, serial = 0xCAFE_BABE.toInt()).use { w ->
            w.writeIdHeader()
            w.writeTagsHeader(vendor = "vezir-test")
            // Synthetic Opus-like packet bytes; the writer doesn't validate
            // them, it just frames them. Use a recognizable pattern.
            w.writeAudioPacket(ByteArray(50) { 0x42 }, granuleSamples48k = 960L)
            w.writeAudioPacket(ByteArray(60) { 0x55 }, granuleSamples48k = 1920L)
        }
        val bytes = baos.toByteArray()
        val pages = parsePages(bytes)
        assertTrue("at least 4 pages expected, got ${pages.size}", pages.size >= 4)

        // Page 0: OpusHead, BOS flag set
        val p0 = pages[0]
        assertEquals(0x02, p0.headerType.toInt() and 0x02)
        assertTrue(p0.payload.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray()))
        // version=1, channels=1, mapping_family=0
        assertEquals(1, p0.payload[8].toInt())
        assertEquals(1, p0.payload[9].toInt())
        assertEquals(0, p0.payload[18].toInt())

        // Page 1: OpusTags
        val p1 = pages[1]
        assertTrue(p1.payload.copyOfRange(0, 8).contentEquals("OpusTags".toByteArray()))

        // Audio pages carry granule positions we set.
        val audioPages = pages.drop(2)
        assertEquals(960L, audioPages[0].granule)
        assertEquals(1920L, audioPages[1].granule)
        // Last page must have EOS bit set.
        assertEquals(0x04, audioPages.last().headerType.toInt() and 0x04)
        // No earlier audio page has EOS set.
        for (p in audioPages.dropLast(1)) {
            assertEquals(0, p.headerType.toInt() and 0x04)
        }

        // Audio payload bytes round-trip exactly.
        assertArrayEquals(ByteArray(50) { 0x42 }, audioPages[0].payload)
        assertArrayEquals(ByteArray(60) { 0x55 }, audioPages[1].payload)
    }

    @Test
    fun crcsValidateOnAllPages() {
        val baos = ByteArrayOutputStream()
        OggOpusWriter(baos, serial = 1).use { w ->
            w.writeIdHeader()
            w.writeTagsHeader()
            for (i in 0 until 5) {
                w.writeAudioPacket(ByteArray(40) { i.toByte() }, granuleSamples48k = (i + 1) * 960L)
            }
        }
        val bytes = baos.toByteArray()
        val pages = parsePages(bytes)
        for ((idx, p) in pages.withIndex()) {
            assertEquals("page $idx CRC mismatch", p.declaredCrc, p.recomputedCrc)
        }
    }
}

/** Minimal Ogg-page parser tailored to validation (no continuation handling needed). */
private data class ParsedPage(
    val headerType: Byte,
    val granule: Long,
    val serial: Int,
    val seq: Int,
    val declaredCrc: Int,
    val recomputedCrc: Int,
    val payload: ByteArray,
)

private fun parsePages(data: ByteArray): List<ParsedPage> {
    val out = mutableListOf<ParsedPage>()
    var pos = 0
    while (pos < data.size) {
        if (data[pos] != 'O'.code.toByte() || data[pos + 1] != 'g'.code.toByte() ||
            data[pos + 2] != 'g'.code.toByte() || data[pos + 3] != 'S'.code.toByte()) {
            error("missing OggS at $pos")
        }
        val bb = ByteBuffer.wrap(data, pos, data.size - pos).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(pos)
        bb.position(pos + 4) // skip OggS
        val version = bb.get()
        require(version.toInt() == 0)
        val headerType = bb.get()
        val granule = bb.long
        val serial = bb.int
        val seq = bb.int
        val declaredCrc = bb.int
        val segCount = (bb.get().toInt() and 0xFF)
        val segTable = ByteArray(segCount)
        bb.get(segTable)
        var payloadLen = 0
        for (b in segTable) payloadLen += b.toInt() and 0xFF
        val payload = ByteArray(payloadLen)
        bb.get(payload)
        val pageEnd = bb.position()

        // Recompute CRC over the page with crc field zeroed.
        val pageBytes = data.copyOfRange(pos, pageEnd)
        for (i in 22..25) pageBytes[i] = 0
        val recomputed = oggCrc(pageBytes)

        out += ParsedPage(headerType, granule, serial, seq, declaredCrc, recomputed, payload)
        pos = pageEnd
    }
    return out
}
