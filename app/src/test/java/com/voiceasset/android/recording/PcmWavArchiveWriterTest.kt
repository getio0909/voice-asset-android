package com.voiceasset.android.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class PcmWavArchiveWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `finalized archive has exact PCM WAV header and metadata`() {
        val file = temporaryFolder.root.resolve("recording.wav")
        val writer = PcmWavArchiveWriter(file)
        repeat(50) { writer.writeFrame(ByteArray(640) { it.toByte() }) }

        val metadata = writer.finish()

        assertEquals(32_044L, metadata.sizeBytes)
        assertEquals(32_000L, metadata.dataBytes)
        assertEquals(1000L, metadata.durationMillis)
        assertTrue(metadata.sha256.matches(Regex("^[0-9a-f]{64}$")))
        RandomAccessFile(file, "r").use { archive ->
            assertEquals("RIFF", ByteArray(4).also(archive::readFully).decodeToString())
            assertEquals(32_036L, archive.readUInt32LE())
            assertEquals("WAVE", ByteArray(4).also(archive::readFully).decodeToString())
            archive.seek(40)
            assertEquals(32_000L, archive.readUInt32LE())
        }
        assertEquals(metadata, writer.finish())
    }

    @Test
    fun `checkpoint makes current audio length durable before finalization`() {
        val file = temporaryFolder.root.resolve("checkpoint.wav")
        val writer = PcmWavArchiveWriter(file, checkpointFrames = 10)
        writer.writeFrame(ByteArray(640))
        writer.checkpoint()

        RandomAccessFile(file, "r").use { archive ->
            archive.seek(40)
            assertEquals(640L, archive.readUInt32LE())
        }
        val metadata = writer.finish()
        assertEquals(20L, metadata.durationMillis)
    }

    @Test
    fun `repair reconstructs lengths from locally retained PCM`() {
        val file = temporaryFolder.root.resolve("recover.wav")
        val writer = PcmWavArchiveWriter(file)
        writer.writeFrame(ByteArray(640) { 1 })
        writer.writeFrame(ByteArray(640) { 2 })
        writer.finish()
        RandomAccessFile(file, "rw").use { archive ->
            archive.seek(4)
            archive.write(byteArrayOf(0, 0, 0, 0))
            archive.seek(40)
            archive.write(byteArrayOf(0, 0, 0, 0))
        }

        val repaired = PcmWavArchiveWriter.repair(file)

        assertEquals(1280L, repaired.dataBytes)
        assertEquals(40L, repaired.durationMillis)
        RandomAccessFile(file, "r").use { archive ->
            archive.seek(40)
            assertEquals(1280L, archive.readUInt32LE())
        }
    }

    @Test
    fun `repair rejects partial samples without truncating the archive`() {
        val file = temporaryFolder.root.resolve("partial.wav")
        val writer = PcmWavArchiveWriter(file)
        writer.writeFrame(ByteArray(640))
        writer.finish()
        file.appendBytes(byteArrayOf(1))
        val length = file.length()

        assertThrows(IllegalArgumentException::class.java) {
            PcmWavArchiveWriter.repair(file)
        }
        assertEquals(length, file.length())
    }
}

private fun RandomAccessFile.readUInt32LE(): Long =
    (0 until 4).fold(0L) { value, index ->
        value or ((read().toLong() and 0xff) shl (index * 8))
    }
