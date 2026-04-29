package com.vezir.android.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.or

/**
 * Pure-Kotlin Ogg page writer that wraps Opus packets produced by
 * [OpusEncoder].
 *
 * Ogg framing (RFC 3533) is small: each page is a 27-byte header
 * (`OggS` magic, version, flags, granule, serial, page seq, CRC, segs) +
 * a segment table + payload. Packets are split into 255-byte segments;
 * the last segment of a packet has length < 255.
 *
 * For Opus (RFC 7845) we emit:
 *   - Page 0: `OpusHead` identification packet,  granule=0, BOS flag set
 *   - Page 1: `OpusTags` comment packet,         granule=0
 *   - Page 2..N-1: audio data,                   granule = cumulative
 *                  decoded samples at 48 kHz
 *   - Page N: last audio page,                   granule unchanged from
 *                                                last update, EOS flag set
 *
 * We don't bother coalescing multiple Opus packets into a single page
 * (page-per-packet is spec-legal and simpler; only mild overhead). For
 * 20 ms frames at 24 kbps we have ~50 pages/sec, which is fine.
 *
 * Caller contract:
 *   1. Construct with target [output] and a stream serial number.
 *   2. Call [writeIdHeader] once.
 *   3. Call [writeTagsHeader] once.
 *   4. Call [writeAudioPacket] one or more times; pass `granuleSamples48k`
 *      = total Opus samples *at 48 kHz* the decoder will have produced
 *      after this packet.
 *   5. Call [finishWithEos] when done; this flushes the last audio page
 *      with EOS set if it wasn't already.
 *   6. Call [close] (or use try/finally) to release the OutputStream.
 *
 * Thread-safety: not thread-safe. Use from a single encoder thread.
 */
class OggOpusWriter(
    output: OutputStream,
    private val serial: Int,
    /** Channels in the encoded stream. We always emit mono. */
    private val channels: Int = 1,
    /** Original sample rate before Opus encoding (16 kHz for our pipeline). */
    private val inputSampleRate: Int = 16_000,
) : Closeable {

    // Always go through a BufferedOutputStream so we don't issue a tiny
    // syscall per Ogg page (~50 pages/s).
    private val output: OutputStream =
        if (output is BufferedOutputStream) output
        else BufferedOutputStream(output)

    constructor(file: File, serial: Int) : this(
        FileOutputStream(file) as OutputStream,
        serial,
    )

    private var pageSeq: Int = 0
    private var ended: Boolean = false

    // Last audio packet written to disk, deferred so we can set EOS on it
    // when the caller finalises.
    private data class PendingAudio(
        val packet: ByteArray,
        val granuleSamples48k: Long,
    )
    private var pendingLast: PendingAudio? = null

    // ──────────────────────────── Headers ────────────────────────────

    /** Emit the OpusHead identification packet (19 bytes). */
    fun writeIdHeader() {
        check(pageSeq == 0) { "writeIdHeader must be called first" }
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OpusHead".toByteArray(Charsets.US_ASCII))     // magic
            put(1.toByte())                                    // version
            put(channels.toByte())                             // channel count
            putShort(0.toShort())                              // pre-skip (samples @48k)
            putInt(inputSampleRate)                            // input sample rate
            putShort(0.toShort())                              // output gain Q7.8
            put(0.toByte())                                    // mapping family = mono/stereo
        }.array()
        writePage(
            data = head,
            granule = 0L,
            isBos = true,
            isEos = false,
        )
    }

    /** Emit the OpusTags comment packet. */
    fun writeTagsHeader(vendor: String = "vezir-android", tags: List<String> = emptyList()) {
        check(pageSeq == 1) { "writeTagsHeader must follow writeIdHeader" }
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val tagBytes = tags.map { it.toByteArray(Charsets.UTF_8) }
        var size = 8 + 4 + vendorBytes.size + 4
        for (t in tagBytes) size += 4 + t.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusTags".toByteArray(Charsets.US_ASCII))
        buf.putInt(vendorBytes.size)
        buf.put(vendorBytes)
        buf.putInt(tagBytes.size)
        for (t in tagBytes) {
            buf.putInt(t.size)
            buf.put(t)
        }
        writePage(buf.array(), granule = 0L, isBos = false, isEos = false)
    }

    // ──────────────────────────── Audio ──────────────────────────────

    /**
     * Write one Opus audio packet. Defers the actual page emit by one
     * packet so that [finishWithEos] can flag the last page with EOS.
     */
    fun writeAudioPacket(packet: ByteArray, granuleSamples48k: Long) {
        check(pageSeq >= 2) { "headers must be written before audio" }
        check(!ended) { "writer already finalized" }
        val prev = pendingLast
        pendingLast = PendingAudio(packet.copyOf(), granuleSamples48k)
        if (prev != null) {
            writePage(prev.packet, prev.granuleSamples48k, isBos = false, isEos = false)
        }
    }

    /**
     * Flush the held-back final audio page with EOS. Idempotent. After
     * this call no more packets may be written.
     */
    fun finishWithEos() {
        if (ended) return
        val pending = pendingLast
        if (pending != null) {
            writePage(pending.packet, pending.granuleSamples48k, isBos = false, isEos = true)
            pendingLast = null
        }
        ended = true
        output.flush()
    }

    override fun close() {
        try {
            finishWithEos()
        } finally {
            output.close()
        }
    }

    // ──────────────────────────── Internals ──────────────────────────

    private fun writePage(
        data: ByteArray,
        granule: Long,
        isBos: Boolean,
        isEos: Boolean,
    ) {
        // Segment table: split data into 255-byte chunks. Final chunk may be
        // 0..254 bytes (and 0 is required if data length is a multiple of 255
        // to mark "packet ends here" rather than "continues to next page").
        val segCount = (data.size / 255) + 1
        require(segCount in 1..255) { "single-page packet too large: ${data.size} bytes" }
        val segTable = ByteArray(segCount)
        var remaining = data.size
        var idx = 0
        while (remaining >= 255) {
            segTable[idx++] = 255.toByte()
            remaining -= 255
        }
        segTable[idx] = remaining.toByte() // final, possibly 0

        // 27-byte header + segment table; CRC is field [22..25] LE, computed
        // over the entire page with that field zeroed.
        val pageSize = 27 + segCount + data.size
        val page = ByteArray(pageSize)
        val bb = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("OggS".toByteArray(Charsets.US_ASCII))                      // 0..3
        bb.put(0.toByte())                                                  // 4 version
        var headerType = 0.toByte()
        if (isBos) headerType = headerType or 0x02.toByte()
        if (isEos) headerType = headerType or 0x04.toByte()
        bb.put(headerType)                                                  // 5
        bb.putLong(granule)                                                 // 6..13
        bb.putInt(serial)                                                   // 14..17
        bb.putInt(pageSeq)                                                  // 18..21
        bb.putInt(0)                                                        // 22..25 CRC (placeholder)
        bb.put(segCount.toByte())                                           // 26
        bb.put(segTable)
        bb.put(data)

        // Compute CRC32 with init=0, polynomial 0x04C11DB7, no reflection,
        // over the whole page. RFC 3533 §6 specifies this exactly.
        val crc = oggCrc(page)
        page[22] = (crc and 0xFF).toByte()
        page[23] = ((crc ushr 8) and 0xFF).toByte()
        page[24] = ((crc ushr 16) and 0xFF).toByte()
        page[25] = ((crc ushr 24) and 0xFF).toByte()

        output.write(page)
        pageSeq++
    }
}

// ──────────────────────────── Ogg CRC table ──────────────────────────

private val OGG_CRC_TABLE: IntArray = run {
    val t = IntArray(256)
    for (i in 0 until 256) {
        var r = i shl 24
        for (k in 0 until 8) {
            r = if ((r and 0x80000000.toInt()) != 0) {
                (r shl 1) xor 0x04C11DB7
            } else {
                r shl 1
            }
        }
        t[i] = r
    }
    t
}

/** RFC 3533 Ogg CRC32 (poly 0x04C11DB7, init 0, no reflection, no xorout). */
internal fun oggCrc(data: ByteArray): Int {
    var crc = 0
    for (b in data) {
        val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
        crc = (crc shl 8) xor OGG_CRC_TABLE[idx]
    }
    return crc
}
