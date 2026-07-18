package com.voiceasset.android.recording

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal class PcmWavArchiveWriter(
    private val file: File,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val channels: Int = DEFAULT_CHANNELS,
    private val checkpointFrames: Int = DEFAULT_CHECKPOINT_FRAMES,
) {
    private val output: RandomAccessFile
    private var dataBytes = 0L
    private var framesSinceCheckpoint = 0
    private var finished: PcmWavArchiveMetadata? = null

    init {
        require(sampleRateHz in SUPPORTED_SAMPLE_RATES) { "WAV sample rate is unsupported" }
        require(channels == 1) { "realtime WAV archive must be mono" }
        require(checkpointFrames > 0) { "WAV checkpoint interval must be positive" }
        require(!file.exists()) { "WAV archive already exists" }
        val parent = checkNotNull(file.parentFile) { "WAV archive must have a parent directory" }
        require(parent.exists() && parent.isDirectory) { "WAV archive directory is unavailable" }
        output = RandomAccessFile(file, "rw")
        try {
            output.setLength(0)
            writeHeader(output, sampleRateHz, channels, dataBytes = 0)
        } catch (exception: Exception) {
            output.close()
            file.delete()
            throw exception
        }
    }

    @Synchronized
    fun writeFrame(pcm: ByteArray) {
        check(finished == null) { "WAV archive is already finalized" }
        require(pcm.isNotEmpty() && pcm.size % BYTES_PER_SAMPLE == 0) { "PCM frame must contain complete samples" }
        require(dataBytes + pcm.size <= MAX_WAV_DATA_BYTES) { "WAV archive exceeds the RIFF size limit" }
        output.seek(WAV_HEADER_BYTES + dataBytes)
        output.write(pcm)
        dataBytes += pcm.size
        framesSinceCheckpoint++
        if (framesSinceCheckpoint >= checkpointFrames) {
            checkpoint()
        }
    }

    @Synchronized
    fun checkpoint() {
        check(finished == null) { "WAV archive is already finalized" }
        writeLengths(output, dataBytes)
        output.fd.sync()
        output.seek(WAV_HEADER_BYTES + dataBytes)
        framesSinceCheckpoint = 0
    }

    @Synchronized
    fun finish(): PcmWavArchiveMetadata {
        finished?.let { return it }
        var failure: Throwable? = null
        try {
            writeLengths(output, dataBytes)
            output.fd.sync()
        } catch (exception: Throwable) {
            failure = exception
        }
        try {
            output.close()
        } catch (exception: Throwable) {
            failure = failure?.let { current -> current.apply { addSuppressed(exception) } } ?: exception
        }
        failure?.let { throw it }
        val metadata = metadata(file, sampleRateHz, channels, dataBytes)
        finished = metadata
        return metadata
    }

    companion object {
        fun repair(file: File): PcmWavArchiveMetadata {
            require(file.isFile && file.length() >= WAV_HEADER_BYTES) { "WAV archive is incomplete" }
            RandomAccessFile(file, "rw").use { archive ->
                val header = ByteArray(WAV_HEADER_BYTES.toInt())
                archive.readFully(header)
                require(header.copyOfRange(0, 4).decodeToString() == "RIFF") { "WAV RIFF marker is invalid" }
                require(header.copyOfRange(8, 12).decodeToString() == "WAVE") { "WAV format marker is invalid" }
                require(header.copyOfRange(12, 16).decodeToString() == "fmt ") { "WAV format chunk is invalid" }
                require(header.copyOfRange(36, 40).decodeToString() == "data") { "WAV data chunk is invalid" }
                require(header.readUInt16LE(20) == PCM_FORMAT) { "WAV archive is not PCM" }
                val channels = header.readUInt16LE(22)
                val sampleRateHz = header.readUInt32LE(24).toInt()
                val bitsPerSample = header.readUInt16LE(34)
                require(channels == 1 && sampleRateHz in SUPPORTED_SAMPLE_RATES && bitsPerSample == BITS_PER_SAMPLE) {
                    "WAV archive format is unsupported"
                }
                val dataBytes = archive.length() - WAV_HEADER_BYTES
                require(dataBytes >= 0 && dataBytes % BYTES_PER_SAMPLE == 0L && dataBytes <= MAX_WAV_DATA_BYTES) {
                    "WAV archive contains an incomplete PCM sample"
                }
                writeLengths(archive, dataBytes)
                archive.fd.sync()
                return metadata(file, sampleRateHz, channels, dataBytes)
            }
        }

        private fun writeHeader(
            output: RandomAccessFile,
            sampleRateHz: Int,
            channels: Int,
            dataBytes: Long,
        ) {
            output.seek(0)
            output.writeBytes("RIFF")
            output.writeUInt32LE(dataBytes + WAV_HEADER_BYTES - 8)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            output.writeUInt32LE(16)
            output.writeUInt16LE(PCM_FORMAT)
            output.writeUInt16LE(channels)
            output.writeUInt32LE(sampleRateHz.toLong())
            output.writeUInt32LE(sampleRateHz.toLong() * channels * BYTES_PER_SAMPLE)
            output.writeUInt16LE(channels * BYTES_PER_SAMPLE)
            output.writeUInt16LE(BITS_PER_SAMPLE)
            output.writeBytes("data")
            output.writeUInt32LE(dataBytes)
        }

        private fun writeLengths(
            output: RandomAccessFile,
            dataBytes: Long,
        ) {
            output.seek(4)
            output.writeUInt32LE(dataBytes + WAV_HEADER_BYTES - 8)
            output.seek(40)
            output.writeUInt32LE(dataBytes)
        }

        private fun metadata(
            file: File,
            sampleRateHz: Int,
            channels: Int,
            dataBytes: Long,
        ): PcmWavArchiveMetadata =
            PcmWavArchiveMetadata(
                sizeBytes = file.length(),
                dataBytes = dataBytes,
                durationMillis = dataBytes * 1000 / (sampleRateHz.toLong() * channels * BYTES_PER_SAMPLE),
                sha256 = file.sha256Hex(),
            )

        private const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        private const val DEFAULT_CHANNELS = 1
        private const val DEFAULT_CHECKPOINT_FRAMES = 50
        private const val PCM_FORMAT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val WAV_HEADER_BYTES = 44L
        private const val MAX_WAV_DATA_BYTES = 0xffff_ffffL - WAV_HEADER_BYTES + 8
        private val SUPPORTED_SAMPLE_RATES = setOf(8000, 16_000, 24_000, 48_000)
    }
}

internal data class PcmWavArchiveMetadata(
    val sizeBytes: Long,
    val dataBytes: Long,
    val durationMillis: Long,
    val sha256: String,
)

private fun RandomAccessFile.writeUInt16LE(value: Int) {
    require(value in 0..0xffff)
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}

private fun RandomAccessFile.writeUInt32LE(value: Long) {
    require(value in 0..0xffff_ffffL)
    repeat(4) { offset ->
        write(((value ushr (offset * 8)) and 0xff).toInt())
    }
}

private fun ByteArray.readUInt16LE(offset: Int): Int = unsigned(offset) or (unsigned(offset + 1) shl 8)

private fun ByteArray.unsigned(offset: Int): Int = this[offset].toInt() and 0xff

private fun ByteArray.readUInt32LE(offset: Int): Long =
    (0 until 4).fold(0L) { value, index ->
        value or ((this[offset + index].toLong() and 0xff) shl (index * 8))
    }

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
