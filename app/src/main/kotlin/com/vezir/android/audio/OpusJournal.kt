package com.vezir.android.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.CRC32

class OpusJournal(
    private val file: File,
    metadata: Metadata,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : Closeable {
    data class Metadata(
        val displayName: String,
        val serial: Int,
        val sampleRate: Int = 16_000,
        val channels: Int = 1,
        val vendor: String = "vezir-android",
        val targetUri: String? = null,
        val displayPath: String? = null,
    )

    data class Packet(
        val bytes: ByteArray,
        val granule: Long,
        val eos: Boolean,
    )

    data class Contents(
        val metadata: Metadata,
        val packets: List<Packet>,
    )

    private val output: FileOutputStream = openPrivateOutput(file)
    private val stream = DataOutputStream(output)
    private var closed = false
    private var ended = false
    private var lastFlushNanos = monotonicNanos()

    var bytesWritten: Long = 0L
        private set

    init {
        require(metadata.sampleRate == 16_000) { "journal requires 16 kHz audio" }
        require(metadata.channels == 1) { "journal requires mono audio" }
        stream.write(MAGIC)
        bytesWritten += MAGIC.size
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF(metadata.displayName)
                data.writeInt(metadata.serial)
                data.writeInt(metadata.sampleRate)
                data.writeInt(metadata.channels)
                data.writeUTF(metadata.vendor)
                data.writeBoolean(metadata.targetUri != null)
                metadata.targetUri?.let(data::writeUTF)
                data.writeBoolean(metadata.displayPath != null)
                metadata.displayPath?.let(data::writeUTF)
            }
        }.toByteArray()
        writeRecord(TYPE_METADATA, payload)
    }

    fun append(packet: ByteArray, granule: Long, eos: Boolean = false) {
        check(!closed) { "journal is closed" }
        check(!ended) { "journal already contains an EOS packet" }
        require(packet.isNotEmpty()) { "Opus packet must not be empty" }
        require(packet.size <= MAX_PACKET_BYTES) { "Opus packet is too large: ${packet.size}" }
        require(granule >= 0L) { "granule must be non-negative" }
        val payload = ByteBuffer.allocate(9 + packet.size)
            .putLong(granule)
            .put(if (eos) 1 else 0)
            .put(packet)
            .array()
        writeRecord(TYPE_PACKET, payload)
        ended = eos
        if (monotonicNanos() - lastFlushNanos >= FLUSH_INTERVAL_NANOS) flush()
    }

    fun flush() {
        check(!closed) { "journal is closed" }
        stream.flush()
        output.fd.sync()
        lastFlushNanos = monotonicNanos()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            stream.flush()
            output.fd.sync()
        } finally {
            stream.close()
        }
    }

    private fun writeRecord(type: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(5).put(type.toByte()).putInt(payload.size).array()
        val crc = CRC32().apply {
            update(header)
            update(payload)
        }
        stream.write(header)
        stream.write(payload)
        stream.writeInt(crc.value.toInt())
        bytesWritten += header.size + payload.size + 4L
    }

    companion object {
        private val MAGIC = byteArrayOf('V'.code.toByte(), 'Z'.code.toByte(), 'J'.code.toByte(), '1'.code.toByte())
        private const val TYPE_METADATA = 1
        private const val TYPE_PACKET = 2
        private const val MAX_PACKET_BYTES = 65_025
        private const val MAX_RECORD_BYTES = 1_048_576
        private const val FLUSH_INTERVAL_NANOS = 1_000_000_000L

        fun read(file: File): Contents = RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < MAGIC.size) throw IOException("journal header is incomplete")
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw IOException("bad journal magic")

            var metadata: Metadata? = null
            val packets = mutableListOf<Packet>()
            var ended = false
            while (input.filePointer < length) {
                if (length - input.filePointer < 5L) break
                val header = ByteArray(5)
                input.readFully(header)
                val headerData = ByteBuffer.wrap(header)
                val type = headerData.get().toInt() and 0xff
                val size = headerData.int
                if (size < 0 || size > MAX_RECORD_BYTES) throw IOException("invalid journal record size: $size")
                if (size.toLong() + 4L > length - input.filePointer) break
                val payload = ByteArray(size)
                input.readFully(payload)
                val expectedCrc = input.readInt()
                val actualCrc = CRC32().apply {
                    update(header)
                    update(payload)
                }.value.toInt()
                if (expectedCrc != actualCrc) throw IOException("journal record checksum mismatch")

                when (type) {
                    TYPE_METADATA -> {
                        if (metadata != null || packets.isNotEmpty()) throw IOException("misplaced journal metadata")
                        metadata = readMetadata(payload)
                    }
                    TYPE_PACKET -> {
                        if (metadata == null) throw IOException("journal metadata is missing")
                        if (ended) throw IOException("journal contains data after EOS")
                        val packet = readPacket(payload)
                        packets += packet
                        ended = packet.eos
                    }
                    else -> throw IOException("unknown journal record type: $type")
                }
            }
            Contents(metadata ?: throw IOException("journal metadata is missing"), packets)
        }

        private fun readMetadata(payload: ByteArray): Metadata {
            val input = DataInputStream(ByteArrayInputStream(payload))
            val metadata = try {
                val displayName = input.readUTF()
                val serial = input.readInt()
                val sampleRate = input.readInt()
                val channels = input.readInt()
                val vendor = input.readUTF()
                var targetUri: String? = null
                var displayPath: String? = null
                if (input.available() > 0) {
                    targetUri = if (input.readBoolean()) input.readUTF() else null
                    displayPath = if (input.readBoolean()) input.readUTF() else null
                }
                Metadata(displayName, serial, sampleRate, channels, vendor, targetUri, displayPath)
            } catch (e: EOFException) {
                throw IOException("incomplete journal metadata", e)
            }
            if (input.available() != 0) throw IOException("extra journal metadata")
            if (metadata.sampleRate != 16_000 || metadata.channels != 1) {
                throw IOException("unsupported journal audio format")
            }
            return metadata
        }

        private fun readPacket(payload: ByteArray): Packet {
            if (payload.size < 10) throw IOException("incomplete Opus packet record")
            val input = ByteBuffer.wrap(payload)
            val granule = input.long
            val eos = when (input.get().toInt()) {
                0 -> false
                1 -> true
                else -> throw IOException("invalid packet EOS flag")
            }
            val bytes = ByteArray(input.remaining())
            input.get(bytes)
            if (bytes.size > MAX_PACKET_BYTES || granule < 0L) throw IOException("invalid Opus packet")
            return Packet(bytes, granule, eos)
        }
    }
}

internal fun openPrivateOutput(file: File): FileOutputStream {
    file.parentFile?.let { parent ->
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("cannot create journal directory: $parent")
        }
    }
    val output = FileOutputStream(file, false)
    try {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
        return output
    } catch (t: Throwable) {
        runCatching { output.close() }
        throw t
    }
}
