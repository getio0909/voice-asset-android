package com.voiceasset.android.recording

import com.voiceasset.core.api.ReliableRealtimeSession
import com.voiceasset.core.model.RecordingErrorCode
import java.io.File

internal interface RealtimeAudioSession {
    fun connect()

    fun appendPcm(
        capturedAtMillis: Long,
        pcm: ByteArray,
    ): Long

    fun finish(
        capturedDurationMillis: Long,
        clientArchiveSha256: String,
    )

    fun cancel()
}

internal class ReliableRealtimeAudioSession(
    private val delegate: ReliableRealtimeSession,
) : RealtimeAudioSession {
    override fun connect() = delegate.connect()

    override fun appendPcm(
        capturedAtMillis: Long,
        pcm: ByteArray,
    ): Long = delegate.appendPcm(capturedAtMillis, pcm)

    override fun finish(
        capturedDurationMillis: Long,
        clientArchiveSha256: String,
    ) = delegate.finish(capturedDurationMillis, clientArchiveSha256)

    override fun cancel() = delegate.cancel()
}

internal data class RealtimeCaptureResult(
    val archive: PcmWavArchiveMetadata,
    val realtimeFinishSubmitted: Boolean,
)

/**
 * Keeps microphone capture independent from realtime delivery. The engine
 * archives each frame before this coordinator hands it to the network session;
 * any network failure disables only streaming and leaves local capture active.
 */
internal class RealtimeCaptureCoordinator(
    private val engine: RealtimeRecordingEngine,
    private val realtimeSession: RealtimeAudioSession,
) {
    private val lock = Any()
    private var state = State.IDLE
    private var realtimeAvailable = true
    private var unavailableReported = false
    private var unavailableCallback: ((Throwable) -> Unit)? = null

    fun start(
        outputFile: File,
        onRealtimeUnavailable: (Throwable) -> Unit,
        onCaptureError: (RecordingErrorCode) -> Unit,
    ) {
        synchronized(lock) {
            check(state == State.IDLE) { "realtime capture coordinator is already used" }
            state = State.STARTING
            unavailableCallback = onRealtimeUnavailable
        }
        try {
            engine.start(
                outputFile = outputFile,
                onFrame = ::publishFrame,
                onRealtimeUnavailable = ::disableRealtime,
                onCaptureError = onCaptureError,
            )
        } catch (exception: Exception) {
            synchronized(lock) { state = State.FAILED }
            runCatching { realtimeSession.cancel() }
            throw exception
        }
        synchronized(lock) { state = State.CAPTURING }
        if (!synchronized(lock) { realtimeAvailable }) {
            return
        }
        try {
            realtimeSession.connect()
        } catch (exception: Exception) {
            disableRealtime(exception)
        }
    }

    fun stop(): RealtimeCaptureResult {
        synchronized(lock) {
            check(state == State.CAPTURING) { "realtime capture coordinator is not active" }
            state = State.STOPPING
        }
        val archive =
            try {
                engine.stop()
            } catch (exception: Exception) {
                synchronized(lock) { state = State.FAILED }
                runCatching { realtimeSession.cancel() }
                throw exception
            }
        val submitFinish = synchronized(lock) { realtimeAvailable }
        val submitted =
            if (submitFinish) {
                try {
                    realtimeSession.finish(archive.durationMillis, archive.sha256)
                    true
                } catch (exception: Exception) {
                    disableRealtime(exception)
                    false
                }
            } else {
                false
            }
        synchronized(lock) { state = State.STOPPED }
        return RealtimeCaptureResult(archive, submitted)
    }

    fun cancel() {
        val shouldCancel =
            synchronized(lock) {
                if (state == State.CANCELLED || state == State.STOPPED || state == State.FAILED) {
                    false
                } else {
                    state = State.CANCELLED
                    true
                }
            }
        if (shouldCancel) {
            engine.release()
            realtimeSession.cancel()
        }
    }

    private fun publishFrame(frame: RealtimePcmFrame) {
        if (!synchronized(lock) { realtimeAvailable }) {
            return
        }
        val acceptedSequence = realtimeSession.appendPcm(frame.capturedAtMillis, frame.pcm)
        check(acceptedSequence == frame.sequence) { "realtime audio sequence diverged from local capture" }
    }

    private fun disableRealtime(error: Throwable) {
        val callback =
            synchronized(lock) {
                if (!realtimeAvailable || unavailableReported) {
                    null
                } else {
                    realtimeAvailable = false
                    unavailableReported = true
                    unavailableCallback
                }
            }
        if (callback != null) {
            runCatching { realtimeSession.cancel() }
            runCatching { callback(error) }
        }
    }

    private enum class State {
        IDLE,
        STARTING,
        CAPTURING,
        STOPPING,
        STOPPED,
        FAILED,
        CANCELLED,
    }
}
