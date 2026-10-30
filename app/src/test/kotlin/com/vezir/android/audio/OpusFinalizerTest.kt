package com.vezir.android.audio

import android.content.Context
import android.content.ContextWrapper
import android.net.TestUri
import android.net.Uri
import com.vezir.android.capture.CountingOutputStream
import com.vezir.android.capture.RecordingStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpusFinalizerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun finalizeProducesValidOggWithJournalValues() {
        val journal = temp.newFile("roundtrip.vzj")
        OpusJournal(journal, OpusJournal.Metadata("original name.ogg", serial = 1234)).use {
            it.append(byteArrayOf(11, 12), granule = 960)
            it.append(byteArrayOf(21, 22, 23), granule = 1_920, eos = true)
        }
        val output = temp.newFile("output.ogg")
        val target = fileTarget(output, "target name.ogg")

        val result = OpusFinalizer(journal).finalize(testContext(), target)

        assertFalse(result.recovered)
        assertEquals("original name.ogg", result.displayName)
        assertEquals(output.length(), result.bytesWritten)
        val pages = parsePages(output.readBytes())
        assertEquals(4, pages.size)
        assertEquals(1234, pages[0].serial)
        assertEquals(16_000, ByteBuffer.wrap(pages[0].payload, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(1, pages[0].payload[9].toInt())
        val vendorLength = ByteBuffer.wrap(pages[1].payload, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals("vezir-android", String(pages[1].payload, 12, vendorLength, Charsets.UTF_8))
        assertEquals(960L, pages[2].granule)
        assertEquals(1_920L, pages[3].granule)
        assertArrayEquals(byteArrayOf(11, 12), pages[2].payload)
        assertArrayEquals(byteArrayOf(21, 22, 23), pages[3].payload)
        assertEquals(4, pages.last().flags.toInt() and 4)
        assertFalse(journal.exists())
    }

    @Test
    fun emptyJournalProducesHeadersOnlyOgg() {
        val journal = temp.newFile("empty.vzj")
        OpusJournal(journal, OpusJournal.Metadata("empty.ogg", serial = 9)).close()
        val output = temp.newFile("empty-output.ogg")

        val result = OpusFinalizer(journal).recover(testContext(), fileTarget(output, "empty.ogg"))

        assertTrue(result.recovered)
        val pages = parsePages(output.readBytes())
        assertEquals(2, pages.size)
        assertTrue(pages[0].payload.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray()))
        assertTrue(pages[1].payload.copyOfRange(0, 8).contentEquals("OpusTags".toByteArray()))
    }

    @Test
    fun repeatedRecoveryDoesNotWriteOutputTwice() {
        val journal = temp.newFile("recovery.vzj")
        val output = temp.newFile("recovered-output.ogg")
        val targetUri = "file://${output.absolutePath}"
        val displayPath = "Music/Vezir/recovered name.ogg"
        OpusJournal(
            journal,
            OpusJournal.Metadata(
                displayName = "recovered name.ogg",
                serial = 77,
                targetUri = targetUri,
                displayPath = displayPath,
            ),
        ).use {
            it.append(byteArrayOf(5, 6, 7), granule = 960, eos = true)
        }
        val target = fileTarget(output, "recovered name.ogg")
        val finalizer = OpusFinalizer(journal)

        val first = finalizer.recover(testContext(), target)
        val firstBytes = output.readBytes()
        val second = finalizer.recover(testContext(), target)

        assertTrue(first.recovered)
        assertTrue(second.recovered)
        assertEquals("recovered name.ogg", first.displayName)
        assertEquals("recovered name.ogg", second.displayName)
        assertEquals(targetUri, first.uri.toString())
        assertEquals(targetUri, second.uri.toString())
        assertEquals(displayPath, first.displayPath)
        assertEquals(displayPath, second.displayPath)
        assertEquals(first.bytesWritten, second.bytesWritten)
        assertArrayEquals(firstBytes, output.readBytes())
        assertFalse(journal.exists())
        assertFalse(finalizer.tempFile.exists())
        assertTrue(finalizer.completionFile.exists())
    }

    @Test
    fun failedCopyPreservesArtifactsForRetry() {
        val journal = temp.newFile("retry.vzj")
        OpusJournal(journal, OpusJournal.Metadata("retry.ogg", serial = 5)).use {
            it.append(byteArrayOf(3, 4), granule = 960, eos = true)
        }
        val failingTarget = target(CountingOutputStream(FailingOutputStream()), "retry.ogg")
        val finalizer = OpusFinalizer(journal)

        try {
            finalizer.recover(testContext(), failingTarget)
            fail("IOException expected")
        } catch (_: IOException) {
        }
        assertTrue(journal.exists())
        assertTrue(finalizer.tempFile.exists())
        assertFalse(finalizer.completionFile.exists())

        val output = temp.newFile("retry-output.ogg")
        val result = finalizer.recover(testContext(), fileTarget(output, "retry.ogg"))
        assertEquals("retry.ogg", result.displayName)
        assertTrue(output.length() > 0L)
        assertFalse(journal.exists())
        assertFalse(finalizer.tempFile.exists())
    }

    @Test
    fun openExistingReusesFileTargetAndDisplayPath() {
        val output = temp.newFile("existing.ogg")
        output.writeBytes(ByteArray(20))
        val uri = TestUri("file://${output.absolutePath}")
        val displayPath = "Music/Vezir/existing.ogg"

        val opened = RecordingStorage.openExisting(
            testContext(),
            uri,
            "existing.ogg",
            displayPath,
            RecordingStorage.MIME_OGG,
        )
        opened.output.use { it.write(byteArrayOf(1, 2, 3)) }

        assertTrue(opened.uri === uri)
        assertEquals(displayPath, opened.displayPath)
        assertEquals("existing.ogg", opened.displayName)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.readBytes())
    }

    private fun fileTarget(file: File, displayName: String): RecordingStorage.RecordingTarget =
        target(
            CountingOutputStream(FileOutputStream(file)),
            displayName,
            TestUri("file://${file.absolutePath}"),
        )

    private fun target(
        output: CountingOutputStream,
        displayName: String,
        uri: Uri = TestUri("content://test/recording"),
    ): RecordingStorage.RecordingTarget = RecordingStorage.RecordingTarget(
        output = output,
        uri = uri,
        displayPath = "Music/Vezir/$displayName",
        displayName = displayName,
        mimeType = RecordingStorage.MIME_OGG,
        pendingValues = null,
    )

    private fun testContext(): Context = uninitialized(ContextWrapper::class.java)

    private fun <T> uninitialized(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private data class Page(
        val flags: Byte,
        val granule: Long,
        val serial: Int,
        val payload: ByteArray,
    )

    private fun parsePages(data: ByteArray): List<Page> {
        val pages = mutableListOf<Page>()
        var position = 0
        while (position < data.size) {
            assertTrue(data.copyOfRange(position, position + 4).contentEquals("OggS".toByteArray()))
            val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            input.position(position + 4)
            assertEquals(0, input.get().toInt())
            val flags = input.get()
            val granule = input.long
            val serial = input.int
            input.position(input.position() + 4)
            val declaredCrc = input.int
            val segmentCount = input.get().toInt() and 0xff
            val segments = ByteArray(segmentCount)
            input.get(segments)
            val payload = ByteArray(segments.sumOf { it.toInt() and 0xff })
            input.get(payload)
            val end = input.position()
            val page = data.copyOfRange(position, end)
            for (index in 22..25) page[index] = 0
            assertEquals(declaredCrc, oggCrc(page))
            pages += Page(flags, granule, serial, payload)
            position = end
        }
        return pages
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(value: Int) {
            throw IOException("injected write failure")
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            throw IOException("injected write failure")
        }
    }
}
