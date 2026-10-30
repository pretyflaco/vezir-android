package com.vezir.android.audio

import android.content.Context
import android.net.Uri
import com.vezir.android.capture.RecordingStorage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

class OpusFinalizer(private val journalFile: File) {
    val tempFile: File = File(journalFile.path + ".ogg.tmp")
    val completionFile: File = File(journalFile.path + ".done")
    private val markerTempFile: File = File(completionFile.path + ".tmp")

    data class Result(
        val uri: Uri,
        val displayPath: String,
        val displayName: String,
        val bytesWritten: Long,
        val recovered: Boolean,
    )

    fun finalize(context: Context, target: RecordingStorage.RecordingTarget): Result =
        finish(context, target, recovered = false)

    fun recover(context: Context, target: RecordingStorage.RecordingTarget): Result =
        finish(context, target, recovered = true)

    fun recover(context: Context): Result {
        readCompletion()?.let { return it.result(recovered = true) }
        val contents = OpusJournal.read(journalFile)
        val metadata = contents.metadata
        val targetUri = metadata.targetUri ?: throw IOException("journal has no target URI")
        val displayPath = metadata.displayPath ?: throw IOException("journal has no display path")
        val target = RecordingStorage.openExisting(
            context = context,
            uri = Uri.parse(targetUri),
            displayName = metadata.displayName,
            displayPath = displayPath,
            mimeType = RecordingStorage.MIME_OGG,
        )
        return finish(context, target, recovered = true, contents = contents)
    }

    private fun finish(
        context: Context,
        target: RecordingStorage.RecordingTarget,
        recovered: Boolean,
        contents: OpusJournal.Contents? = null,
    ): Result {
        readCompletion()?.let { completed ->
            return Result(
                target.uri,
                completed.displayPath,
                completed.displayName,
                completed.bytesWritten,
                recovered,
            )
        }

        val journal = contents ?: OpusJournal.read(journalFile)
        var completed = false
        try {
            writeOgg(journal)
            tempFile.inputStream().use { input ->
                target.output.use { output -> input.copyTo(output) }
            }
            target.finalize(context)
            val bytesWritten = tempFile.length()
            writeCompletion(
                displayName = journal.metadata.displayName,
                displayPath = target.displayPath,
                targetUri = target.uri.toString(),
                bytesWritten = bytesWritten,
            )
            completed = true
            return Result(
                target.uri,
                target.displayPath,
                journal.metadata.displayName,
                bytesWritten,
                recovered,
            )
        } finally {
            if (completed) {
                tempFile.delete()
                journalFile.delete()
                markerTempFile.delete()
            }
        }
    }

    private fun writeOgg(contents: OpusJournal.Contents) {
        openPrivateOutput(tempFile).use { output ->
            val metadata = contents.metadata
            val ogg = OggOpusWriter(
                output = output,
                serial = metadata.serial,
                channels = metadata.channels,
                inputSampleRate = metadata.sampleRate,
            )
            ogg.writeIdHeader()
            ogg.writeTagsHeader(vendor = metadata.vendor)
            contents.packets.forEach { packet ->
                ogg.writeAudioPacket(packet.bytes, packet.granule)
            }
            ogg.finishWithEos()
            output.fd.sync()
        }
    }

    private fun writeCompletion(
        displayName: String,
        displayPath: String,
        targetUri: String,
        bytesWritten: Long,
    ) {
        openPrivateOutput(markerTempFile).use { output ->
            DataOutputStream(output).use { data ->
                data.writeUTF(displayName)
                data.writeUTF(displayPath)
                data.writeUTF(targetUri)
                data.writeLong(bytesWritten)
                data.flush()
                output.fd.sync()
            }
        }
        if (!markerTempFile.renameTo(completionFile)) throw IOException("cannot create completion marker")
    }

    private fun readCompletion(): Completion? {
        if (!completionFile.isFile) return null
        return DataInputStream(completionFile.inputStream().buffered()).use { input ->
            Completion(input.readUTF(), input.readUTF(), input.readUTF(), input.readLong())
        }
    }

    private fun Completion.result(recovered: Boolean) = Result(
        uri = Uri.parse(targetUri),
        displayPath = displayPath,
        displayName = displayName,
        bytesWritten = bytesWritten,
        recovered = recovered,
    )

    private data class Completion(
        val displayName: String,
        val displayPath: String,
        val targetUri: String,
        val bytesWritten: Long,
    )

    companion object {
        /** Remove completed temp files even when their journal was already deleted. */
        fun cleanupCompletedArtifacts(directory: File) {
            directory.listFiles { file -> file.name.endsWith(".done") }?.forEach { completion ->
                val journal = File(completion.path.removeSuffix(".done"))
                File(journal.path + ".ogg.tmp").delete()
                File(completion.path + ".tmp").delete()
                journal.delete()
            }
        }
    }
}
