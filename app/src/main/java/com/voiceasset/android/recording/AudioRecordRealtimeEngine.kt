package com.voiceasset.android.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voiceasset.core.model.RecordingErrorCode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal data class RealtimePcmFrame(
    val sequence: Long,
    val capturedAtMillis: Long,
    val pcm: ByteArray,
)

internal interface RealtimeRecordingEngine {
    fun start(
        outputFile: File,
        onFrame: (RealtimePcmFrame) -> Unit,
        onRealtimeUnavailable: (Throwable) -> Unit,
        onCaptureError: (RecordingErrorCode) -> Unit,
    )

    fun stop(): PcmWavArchiveMetadata

    fun release()
}

internal class AudioRecordRealtimeEngine(
    private val sourceFactory: PcmSourceFactory = AndroidPcmSourceFactory,
) : RealtimeRecordingEngine {
    private val running = AtomicBoolean()
    private val captureFailure = AtomicReference<RecordingErrorCode>()
    private var source: PcmSource? = null
    private var archive: PcmWavArchiveWriter? = null
    private var captureThread: Thread? = null

    @Synchronized
    override fun start(
        outputFile: File,
        onFrame: (RealtimePcmFrame) -> Unit,
        onRealtimeUnavailable: (Throwable) -> Unit,
        onCaptureError: (RecordingErrorCode) -> Unit,
    ) {
        check(source == null && archive == null && captureThread == null) { "realtime recording engine is already active" }
        val createdArchive = PcmWavArchiveWriter(outputFile)
        val createdSource =
            try {
                sourceFactory.create()
            } catch (exception: Exception) {
                createdArchive.finish()
                outputFile.delete()
                throw exception
            }
        try {
            createdSource.start()
        } catch (exception: Exception) {
            createdSource.release()
            createdArchive.finish()
            outputFile.delete()
            throw exception
        }
        source = createdSource
        archive = createdArchive
        captureFailure.set(null)
        running.set(true)
        captureThread =
            Thread(
                {
                    captureLoop(
                        createdSource,
                        createdArchive,
                        onFrame,
                        onRealtimeUnavailable,
                        onCaptureError,
                    )
                },
                CAPTURE_THREAD_NAME,
            ).apply {
                isDaemon = true
                start()
            }
    }

    @Synchronized
    override fun stop(): PcmWavArchiveMetadata {
        val activeSource = checkNotNull(source) { "realtime recording engine is not active" }
        val activeArchive = checkNotNull(archive)
        val activeThread = checkNotNull(captureThread)
        running.set(false)
        runCatching { activeSource.stop() }
        activeThread.join(STOP_TIMEOUT_MILLIS)
        if (activeThread.isAlive) {
            activeSource.release()
            activeThread.interrupt()
            activeThread.join(STOP_TIMEOUT_MILLIS)
        }
        check(!activeThread.isAlive) { "realtime audio capture did not stop" }
        activeSource.release()
        val metadata = activeArchive.finish()
        source = null
        archive = null
        captureThread = null
        return metadata
    }

    @Synchronized
    override fun release() {
        if (source == null) {
            return
        }
        runCatching { stop() }
    }

    fun captureError(): RecordingErrorCode? = captureFailure.get()

    private fun captureLoop(
        activeSource: PcmSource,
        activeArchive: PcmWavArchiveWriter,
        onFrame: (RealtimePcmFrame) -> Unit,
        onRealtimeUnavailable: (Throwable) -> Unit,
        onCaptureError: (RecordingErrorCode) -> Unit,
    ) {
        var sequence = 0L
        var realtimeEnabled = true
        val frame = ByteArray(FRAME_BYTES)
        try {
            while (running.get()) {
                val frameBytes = readFrame(activeSource, frame)
                if (frameBytes == 0) {
                    continue
                }
                val captured = frame.copyOf(frameBytes)
                activeArchive.writeFrame(captured)
                if (realtimeEnabled) {
                    try {
                        onFrame(
                            RealtimePcmFrame(
                                sequence = sequence,
                                capturedAtMillis = sequence * FRAME_DURATION_MILLIS,
                                pcm = captured.copyOf(),
                            ),
                        )
                    } catch (exception: Exception) {
                        realtimeEnabled = false
                        runCatching { onRealtimeUnavailable(exception) }
                    }
                }
                sequence++
            }
        } catch (exception: Exception) {
            if (running.getAndSet(false)) {
                val error = mapCaptureError(exception)
                captureFailure.set(error)
                runCatching { onCaptureError(error) }
            }
        } finally {
            runCatching { activeSource.stop() }
        }
    }

    private fun readFrame(
        activeSource: PcmSource,
        frame: ByteArray,
    ): Int {
        var offset = 0
        while (offset < frame.size && running.get()) {
            val read = activeSource.read(frame, offset, frame.size - offset)
            when {
                read > 0 -> offset += read
                read == 0 -> Thread.yield()
                else -> throw AudioCaptureException(read)
            }
        }
        if (offset % BYTES_PER_SAMPLE != 0) {
            throw AudioCaptureException(AudioRecord.ERROR_BAD_VALUE)
        }
        return offset
    }

    private fun mapCaptureError(exception: Exception): RecordingErrorCode =
        when (exception) {
            is SecurityException -> RecordingErrorCode.PERMISSION_DENIED
            is AudioCaptureException -> RecordingErrorCode.ENGINE_FAILURE
            else -> RecordingErrorCode.MICROPHONE_UNAVAILABLE
        }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        const val FRAME_DURATION_MILLIS = 20L
        const val FRAME_BYTES = SAMPLE_RATE_HZ * CHANNELS * BYTES_PER_SAMPLE * FRAME_DURATION_MILLIS.toInt() / 1000
        const val STOP_TIMEOUT_MILLIS = 5000L
        const val CAPTURE_THREAD_NAME = "voiceasset-realtime-audio"
    }
}

internal fun interface PcmSourceFactory {
    fun create(): PcmSource
}

internal interface PcmSource {
    fun start()

    fun read(
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    fun stop()

    fun release()
}

private object AndroidPcmSourceFactory : PcmSourceFactory {
    @SuppressLint("MissingPermission")
    override fun create(): PcmSource {
        val minimumBuffer =
            AudioRecord.getMinBufferSize(
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimumBuffer > 0) { "device does not support realtime PCM capture" }
        val format =
            AudioFormat
                .Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
        val recorder =
            AudioRecord
                .Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minimumBuffer, 640 * 8))
                .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "realtime audio recorder did not initialize"
        }
        return AndroidPcmSource(recorder)
    }
}

private class AndroidPcmSource(
    private val recorder: AudioRecord,
) : PcmSource {
    override fun start() {
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "realtime audio recorder did not start"
        }
    }

    override fun read(
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int = recorder.read(target, offset, length, AudioRecord.READ_BLOCKING)

    override fun stop() {
        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            recorder.stop()
        }
    }

    override fun release() {
        recorder.release()
    }
}

private class AudioCaptureException(
    code: Int,
) : IllegalStateException("AudioRecord read failed with code $code")
