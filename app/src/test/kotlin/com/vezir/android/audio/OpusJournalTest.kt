package com.vezir.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.CRC32

class OpusJournalTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun roundtripPreservesMetadataPacketsAndTimeline() {
        val file = temp.newFile("recording.vzj")
        val metadata = OpusJournal.Metadata(
            displayName = "paused meeting.ogg",
            serial = 0x10203040,
            targetUri = "content://media/original/42",
            displayPath = "Music/Vezir/paused meeting.ogg",
        )
        var clock = 10L
        val journal = OpusJournal(file, metadata) { clock }
        journal.append(byteArrayOf(1, 2, 3), granule = 960)
        clock += 1_000_000_000L
        journal.append(byteArrayOf(4, 5), granule = 2_880, eos = true)
        assertEquals(journal.bytesWritten, file.length())
        journal.close()

        val contents = OpusJournal.read(file)
        assertEquals(metadata, contents.metadata)
        assertEquals(2, contents.packets.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), contents.packets[0].bytes)
        assertEquals(960L, contents.packets[0].granule)
        assertFalse(contents.packets[0].eos)
        assertArrayEquals(byteArrayOf(4, 5), contents.packets[1].bytes)
        assertEquals(2_880L, contents.packets[1].granule)
        assertTrue(contents.packets[1].eos)
    }

    @Test
    fun partialTrailingRecordIsIgnored() {
        val file = temp.newFile("partial.vzj")
        OpusJournal(file, metadata()).use {
            it.append(byteArrayOf(7, 8), granule = 960)
        }
        FileOutputStream(file, true).use { output ->
            output.write(ByteBuffer.allocate(9).put(2).putInt(12).put(byteArrayOf(1, 2, 3, 4)).array())
        }

        val contents = OpusJournal.read(file)
        assertEquals(1, contents.packets.size)
        assertArrayEquals(byteArrayOf(7, 8), contents.packets.single().bytes)
    }

    @Test
    fun corruptCompleteRecordIsRejected() {
        val file = temp.newFile("corrupt.vzj")
        OpusJournal(file, metadata()).use {
            it.append(byteArrayOf(7, 8), granule = 960)
        }
        val bytes = file.readBytes()
        bytes[bytes.size - 6] = (bytes[bytes.size - 6] + 1).toByte()
        file.writeBytes(bytes)

        assertIOException { OpusJournal.read(file) }
    }

    @Test
    fun badMagicAndOversizedRecordsAreRejected() {
        val badMagic = temp.newFile("magic.vzj")
        badMagic.writeBytes(byteArrayOf(0, 1, 2, 3))
        assertIOException { OpusJournal.read(badMagic) }

        val oversized = temp.newFile("oversized.vzj")
        oversized.writeBytes(
            ByteBuffer.allocate(9)
                .put(byteArrayOf(86, 90, 74, 49))
                .put(2)
                .putInt(Int.MAX_VALUE)
                .array(),
        )
        assertIOException { OpusJournal.read(oversized) }
    }

    @Test
    fun missingMetadataIsRejected() {
        val file = temp.newFile("missing.vzj")
        file.writeBytes(byteArrayOf(86, 90, 74, 49))
        assertIOException { OpusJournal.read(file) }
    }

    @Test
    fun legacyMetadataDefaultsTargetLocationToNull() {
        val file = temp.newFile("legacy.vzj")
        val payload = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { data ->
                data.writeUTF("legacy.ogg")
                data.writeInt(12)
                data.writeInt(16_000)
                data.writeInt(1)
                data.writeUTF("vezir-android")
            }
        }.toByteArray()
        val header = ByteBuffer.allocate(5).put(1).putInt(payload.size).array()
        val crc = CRC32().apply {
            update(header)
            update(payload)
        }.value.toInt()
        file.writeBytes(
            byteArrayOf(86, 90, 74, 49) + header + payload + ByteBuffer.allocate(4).putInt(crc).array(),
        )

        val metadata = OpusJournal.read(file).metadata
        assertEquals("legacy.ogg", metadata.displayName)
        assertNull(metadata.targetUri)
        assertNull(metadata.displayPath)
    }

    @Test
    fun journalIsOwnerReadWriteOnly() {
        val file = temp.newFile("private.vzj")
        OpusJournal(file, metadata()).close()

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file.toPath()),
        )
    }

    private fun metadata() = OpusJournal.Metadata("recording.ogg", serial = 42)

    private fun assertIOException(block: () -> Unit) {
        try {
            block()
            fail("IOException expected")
        } catch (_: IOException) {
        }
    }
}
