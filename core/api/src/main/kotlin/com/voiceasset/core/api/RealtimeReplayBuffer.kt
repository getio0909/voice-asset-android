package com.voiceasset.core.api

/**
 * Retains unacknowledged PCM frames across a short WebSocket interruption.
 * The durable local recording remains the source of truth; capacity exhaustion
 * is explicit so callers can stop realtime mode without losing the archive.
 */
class RealtimeReplayBuffer(
    private val maxPendingBytes: Int = DEFAULT_MAX_PENDING_BYTES,
) {
    private val pending = ArrayDeque<BufferedFrame>()
    private var sessionId: String? = null
    private var nextSequence = 0L
    private var lastAcknowledgedSequence = -1L
    private var acknowledgedBytes = 0L
    private var capturedBytes = 0L
    private var pendingBytes = 0
    private var lastCapturedAtMillis = -1L
    private var closed = false

    init {
        require(maxPendingBytes in 2..MAX_PENDING_BYTES_LIMIT && maxPendingBytes % 2 == 0) {
            "realtime replay capacity is invalid"
        }
    }

    @Synchronized
    fun append(
        capturedAtMillis: Long,
        pcm: ByteArray,
    ): Long {
        check(!closed) { "realtime replay buffer is closed" }
        require(capturedAtMillis >= 0 && capturedAtMillis >= lastCapturedAtMillis) {
            "realtime capture time moved backwards"
        }
        require(pcm.isNotEmpty() && pcm.size <= MAX_REALTIME_FRAME_BYTES && pcm.size % 2 == 0) {
            "PCM frame size is invalid"
        }
        if (pcm.size > maxPendingBytes - pendingBytes) {
            throw RealtimeReplayCapacityException()
        }
        val sequence = nextSequence
        nextSequence++
        capturedBytes += pcm.size
        pendingBytes += pcm.size
        lastCapturedAtMillis = capturedAtMillis
        pending.addLast(
            BufferedFrame(
                sequence = sequence,
                capturedAtMillis = capturedAtMillis,
                pcm = pcm.copyOf(),
                cumulativeBytes = capturedBytes,
            ),
        )
        return sequence
    }

    @Synchronized
    fun reconcileReady(event: RealtimeReadyEvent) {
        check(!closed) { "realtime replay buffer is closed" }
        val currentSessionId = sessionId
        require(currentSessionId == null || currentSessionId == event.sessionId) {
            "server changed the realtime session id"
        }
        require(event.nextSequence in (lastAcknowledgedSequence + 1)..nextSequence) {
            "server realtime progress is inconsistent"
        }
        sessionId = event.sessionId
        acknowledgeThrough(event.nextSequence - 1, expectedReceivedBytes = null)
    }

    @Synchronized
    fun adoptSession(sessionId: String) {
        check(!closed) { "realtime replay buffer is closed" }
        RealtimeResumeEvent(
            protocolVersion = REALTIME_PROTOCOL_VERSION,
            sessionId = sessionId,
            lastAcknowledgedSequence = lastAcknowledgedSequence,
        )
        val currentSessionId = this.sessionId
        require(currentSessionId == null || currentSessionId == sessionId) {
            "server changed the realtime session id"
        }
        this.sessionId = sessionId
    }

    @Synchronized
    fun acknowledge(event: RealtimeAckEvent) {
        check(!closed) { "realtime replay buffer is closed" }
        require(event.sessionId == sessionId) { "acknowledgement session does not match" }
        require(event.acknowledgedSequence in lastAcknowledgedSequence until nextSequence) {
            "acknowledgement sequence is inconsistent"
        }
        acknowledgeThrough(event.acknowledgedSequence, event.receivedBytes)
    }

    @Synchronized
    fun resumeEvent(): RealtimeResumeEvent =
        RealtimeResumeEvent(
            protocolVersion = REALTIME_PROTOCOL_VERSION,
            sessionId = checkNotNull(sessionId) { "realtime session is not ready" },
            lastAcknowledgedSequence = lastAcknowledgedSequence,
        )

    @Synchronized
    fun pendingEvents(): List<RealtimeAudioEvent> {
        val activeSessionId = checkNotNull(sessionId) { "realtime session is not ready" }
        return pending.map { frame ->
            RealtimeAudioEvent.fromPcm(
                sessionId = activeSessionId,
                sequence = frame.sequence,
                capturedAtMillis = frame.capturedAtMillis,
                pcm = frame.pcm.copyOf(),
            )
        }
    }

    @Synchronized
    fun finishEvent(
        capturedDurationMillis: Long,
        clientArchiveSha256: String,
    ): RealtimeFinishEvent =
        RealtimeFinishEvent(
            sessionId = checkNotNull(sessionId) { "realtime session is not ready" },
            finalSequence = nextSequence - 1,
            capturedDurationMillis = capturedDurationMillis,
            clientArchiveSha256 = clientArchiveSha256,
        )

    @Synchronized
    fun snapshot(): RealtimeReplaySnapshot =
        RealtimeReplaySnapshot(
            sessionId = sessionId,
            nextSequence = nextSequence,
            lastAcknowledgedSequence = lastAcknowledgedSequence,
            acknowledgedBytes = acknowledgedBytes,
            capturedBytes = capturedBytes,
            pendingBytes = pendingBytes,
            pendingFrames = pending.size,
            lastCapturedAtMillis = lastCapturedAtMillis.takeIf { it >= 0 },
            closed = closed,
        )

    @Synchronized
    fun close() {
        closed = true
        pending.clear()
        pendingBytes = 0
    }

    private fun acknowledgeThrough(
        sequence: Long,
        expectedReceivedBytes: Long?,
    ) {
        if (sequence == lastAcknowledgedSequence) {
            require(expectedReceivedBytes == null || expectedReceivedBytes == acknowledgedBytes) {
                "server byte acknowledgement is inconsistent"
            }
            return
        }
        require(sequence > lastAcknowledgedSequence && sequence < nextSequence) {
            "acknowledgement sequence is inconsistent"
        }
        val target = pending.firstOrNull { frame -> frame.sequence == sequence }
        val resolvedBytes = checkNotNull(target?.cumulativeBytes) { "acknowledged frame is unavailable" }
        require(expectedReceivedBytes == null || expectedReceivedBytes == resolvedBytes) {
            "server byte acknowledgement is inconsistent"
        }
        while (pending.isNotEmpty() && pending.first().sequence <= sequence) {
            val acknowledged = pending.removeFirst()
            pendingBytes -= acknowledged.pcm.size
        }
        lastAcknowledgedSequence = sequence
        acknowledgedBytes = resolvedBytes
    }

    private data class BufferedFrame(
        val sequence: Long,
        val capturedAtMillis: Long,
        val pcm: ByteArray,
        val cumulativeBytes: Long,
    )

    private companion object {
        const val DEFAULT_MAX_PENDING_BYTES = 12 * 1024 * 1024
        const val MAX_PENDING_BYTES_LIMIT = 64 * 1024 * 1024
    }
}

data class RealtimeReplaySnapshot(
    val sessionId: String?,
    val nextSequence: Long,
    val lastAcknowledgedSequence: Long,
    val acknowledgedBytes: Long,
    val capturedBytes: Long,
    val pendingBytes: Int,
    val pendingFrames: Int,
    val lastCapturedAtMillis: Long?,
    val closed: Boolean,
)

class RealtimeReplayCapacityException :
    IllegalStateException(
        "Realtime replay capacity was exhausted; continue the durable local recording without live transcription.",
    )
