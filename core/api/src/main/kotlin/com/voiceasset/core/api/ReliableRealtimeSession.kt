package com.voiceasset.core.api

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

enum class ReliableRealtimeState {
    IDLE,
    CONNECTING,
    READY,
    DISCONNECTED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class RealtimeReconnectPolicy(
    val initialDelayMillis: Long = 1000,
    val maximumDelayMillis: Long = 15_000,
    val reconnectWindowMillis: Long = 75_000,
) {
    init {
        require(initialDelayMillis > 0) { "initial reconnect delay must be positive" }
        require(maximumDelayMillis >= initialDelayMillis) { "maximum reconnect delay is invalid" }
        require(reconnectWindowMillis >= maximumDelayMillis) { "reconnect window is too short" }
    }
}

interface ReliableRealtimeListener {
    fun onStateChanged(state: ReliableRealtimeState) = Unit

    fun onReady(snapshot: RealtimeReplaySnapshot) = Unit

    fun onAcknowledged(snapshot: RealtimeReplaySnapshot) = Unit

    fun onPartialTranscript(event: RealtimePartialTranscriptEvent) = Unit

    fun onFinalTranscript(event: RealtimeFinalTranscriptEvent) = Unit

    fun onServerError(event: RealtimeErrorEvent) = Unit

    fun onDisconnected(error: IOException) = Unit
}

/**
 * Coordinates one durable client recording with reconnect, lost-ACK replay,
 * bounded WebSocket queueing, and heartbeat delivery. Callers must archive a
 * PCM frame locally before passing it to [appendPcm].
 */
class ReliableRealtimeSession(
    private val client: VoiceAssetRealtimeClient,
    private val startEvent: RealtimeStartEvent,
    private val listener: ReliableRealtimeListener,
    maxPendingBytes: Int = DEFAULT_PENDING_BYTES,
    private val reconnectPolicy: RealtimeReconnectPolicy = RealtimeReconnectPolicy(),
) : RealtimeConnectionListener {
    private val lock = Any()
    private val buffer = RealtimeReplayBuffer(maxPendingBytes)
    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, SCHEDULER_THREAD_NAME).apply { isDaemon = true }
        }
    private var state = ReliableRealtimeState.IDLE
    private var connection: RealtimeConnection? = null
    private var sentThroughSequence = -1L
    private var finishRequest: FinishRequest? = null
    private var finishSent = false
    private var reconnectAttempt = 0
    private var disconnectedAtNanos: Long? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var finalResult: RealtimeFinalTranscriptEvent? = null

    fun connect() {
        val changed =
            synchronized(lock) {
                check(state == ReliableRealtimeState.IDLE || state == ReliableRealtimeState.DISCONNECTED) {
                    "realtime session cannot connect from $state"
                }
                reconnectFuture?.cancel(false)
                reconnectFuture = null
                state = ReliableRealtimeState.CONNECTING
                true
            }
        if (changed) {
            listener.onStateChanged(ReliableRealtimeState.CONNECTING)
            openConnection()
        }
    }

    fun appendPcm(
        capturedAtMillis: Long,
        pcm: ByteArray,
    ): Long {
        val sequence =
            synchronized(lock) {
                check(!state.isTerminal() && finishRequest == null) { "realtime session is not accepting audio" }
                buffer.append(capturedAtMillis, pcm)
            }
        flush()
        return sequence
    }

    fun finish(
        capturedDurationMillis: Long,
        clientArchiveSha256: String,
    ) {
        require(capturedDurationMillis >= 0) { "captured duration is invalid" }
        require(SHA256.matches(clientArchiveSha256)) { "archive checksum must be lowercase SHA-256" }
        synchronized(lock) {
            check(!state.isTerminal() && finishRequest == null) { "realtime session cannot finish" }
            finishRequest = FinishRequest(capturedDurationMillis, clientArchiveSha256)
        }
        flush()
    }

    fun reconnectNow() {
        val shouldConnect =
            synchronized(lock) {
                if (state != ReliableRealtimeState.DISCONNECTED) {
                    false
                } else {
                    reconnectFuture?.cancel(false)
                    reconnectFuture = null
                    state = ReliableRealtimeState.CONNECTING
                    true
                }
            }
        if (shouldConnect) {
            listener.onStateChanged(ReliableRealtimeState.CONNECTING)
            openConnection()
        }
    }

    fun cancel() {
        val activeConnection =
            synchronized(lock) {
                if (state.isTerminal()) {
                    return
                }
                state = ReliableRealtimeState.CANCELLED
                cancelScheduledWork()
                buffer.close()
                connection.also { connection = null }
            }
        activeConnection?.cancel()
        scheduler.shutdownNow()
        listener.onStateChanged(ReliableRealtimeState.CANCELLED)
    }

    fun state(): ReliableRealtimeState = synchronized(lock) { state }

    fun snapshot(): RealtimeReplaySnapshot = buffer.snapshot()

    override fun onOpen(connection: RealtimeConnection) {
        val firstEvent =
            synchronized(lock) {
                if (state != ReliableRealtimeState.CONNECTING) {
                    null
                } else {
                    this.connection = connection
                    val snapshot = buffer.snapshot()
                    if (snapshot.sessionId == null) startEvent else buffer.resumeEvent()
                }
            }
        if (firstEvent == null) {
            connection.cancel()
            return
        }
        if (!connection.send(firstEvent)) {
            connection.cancel()
        }
    }

    override fun onEvent(
        connection: RealtimeConnection,
        event: RealtimeServerEvent,
    ) {
        if (!isCurrent(connection)) {
            return
        }
        if (event !is RealtimeReadyEvent && !matchesActiveSession(event)) {
            failProtocol(connection)
            return
        }
        when (event) {
            is RealtimeReadyEvent -> acceptReady(connection, event)
            is RealtimeAckEvent -> acceptAcknowledgement(connection, event)
            is RealtimePartialTranscriptEvent -> listener.onPartialTranscript(event)
            is RealtimeFinalTranscriptEvent -> acceptFinalTranscript(connection, event)
            is RealtimeErrorEvent -> acceptServerError(connection, event)
            is RealtimeClosedEvent -> acceptServerClose(connection, event)
            is RealtimeHeartbeatAckEvent -> Unit
        }
    }

    override fun onClosing(
        connection: RealtimeConnection,
        code: Int,
        reason: String,
    ) = Unit

    override fun onClosed(
        connection: RealtimeConnection,
        code: Int,
        reason: String,
    ) {
        disconnect(
            connection,
            VoiceAssetConnectionException(
                "VoiceAsset realtime connection closed before the session completed.",
                IOException("WebSocket closed with code $code"),
            ),
        )
    }

    override fun onFailure(
        connection: RealtimeConnection,
        error: IOException,
    ) {
        disconnect(connection, error)
    }

    private fun openConnection() {
        val created = client.connect(this)
        val cancelCreated =
            synchronized(lock) {
                when {
                    state != ReliableRealtimeState.CONNECTING -> true
                    connection == null -> {
                        connection = created
                        false
                    }
                    connection !== created -> true
                    else -> false
                }
            }
        if (cancelCreated) {
            created.cancel()
        }
    }

    private fun acceptReady(
        connection: RealtimeConnection,
        event: RealtimeReadyEvent,
    ) {
        val snapshot =
            try {
                synchronized(lock) {
                    if (this.connection !== connection || state != ReliableRealtimeState.CONNECTING) {
                        return
                    }
                    buffer.reconcileReady(event)
                    state = ReliableRealtimeState.READY
                    sentThroughSequence = event.nextSequence - 1
                    finishSent = false
                    reconnectAttempt = 0
                    disconnectedAtNanos = null
                    scheduleHeartbeat(event.heartbeatIntervalMillis)
                    buffer.snapshot()
                }
            } catch (_: IllegalArgumentException) {
                failProtocol(connection)
                return
            }
        listener.onStateChanged(ReliableRealtimeState.READY)
        listener.onReady(snapshot)
        flush()
    }

    private fun acceptAcknowledgement(
        connection: RealtimeConnection,
        event: RealtimeAckEvent,
    ) {
        val snapshot =
            try {
                synchronized(lock) {
                    if (this.connection !== connection || state != ReliableRealtimeState.READY) {
                        return
                    }
                    buffer.acknowledge(event)
                    buffer.snapshot()
                }
            } catch (_: IllegalArgumentException) {
                failProtocol(connection)
                return
            }
        listener.onAcknowledged(snapshot)
        flush()
    }

    private fun acceptServerError(
        connection: RealtimeConnection,
        event: RealtimeErrorEvent,
    ) {
        listener.onServerError(event)
        if (event.code == "session_exists" && event.retriable && event.sessionId != null) {
            try {
                synchronized(lock) {
                    buffer.adoptSession(event.sessionId)
                }
            } catch (_: IllegalArgumentException) {
                failProtocol(connection)
                return
            }
            forceReconnect(connection)
            return
        }
        if (event.code == "sequence_gap" && event.retriable && event.expectedSequence != null) {
            val valid =
                synchronized(lock) {
                    val snapshot = buffer.snapshot()
                    val expected = event.expectedSequence
                    if (expected in (snapshot.lastAcknowledgedSequence + 1)..<snapshot.nextSequence) {
                        sentThroughSequence = expected - 1
                        finishSent = false
                        true
                    } else {
                        false
                    }
                }
            if (valid) {
                flush()
            } else {
                failProtocol(connection)
            }
        }
    }

    private fun acceptFinalTranscript(
        connection: RealtimeConnection,
        event: RealtimeFinalTranscriptEvent,
    ) {
        val notify =
            synchronized(lock) {
                when (val existing = finalResult) {
                    null -> {
                        finalResult = event
                        true
                    }
                    event -> false
                    else -> null
                }
            }
        if (notify == null) {
            failProtocol(connection)
        } else if (notify) {
            listener.onFinalTranscript(event)
        }
    }

    private fun acceptServerClose(
        connection: RealtimeConnection,
        event: RealtimeClosedEvent,
    ) {
        if (event.reason == "completed" && synchronized(lock) { finalResult == null }) {
            failProtocol(connection)
            return
        }
        val terminalState =
            if (event.reason == "completed") {
                ReliableRealtimeState.COMPLETED
            } else {
                ReliableRealtimeState.FAILED
            }
        synchronized(lock) {
            state = terminalState
            cancelScheduledWork()
            buffer.close()
            this.connection = null
        }
        scheduler.shutdown()
        listener.onStateChanged(terminalState)
    }

    private fun flush() {
        var cancelConnection: RealtimeConnection? = null
        synchronized(lock) {
            val activeConnection = connection ?: return
            if (state != ReliableRealtimeState.READY) {
                return
            }
            for (event in buffer.pendingEvents()) {
                if (event.sequence <= sentThroughSequence) {
                    continue
                }
                if (activeConnection.queueSize() >= MAX_WEBSOCKET_QUEUE_BYTES) {
                    break
                }
                if (!activeConnection.send(event)) {
                    cancelConnection = activeConnection
                    break
                }
                sentThroughSequence = event.sequence
            }
            val snapshot = buffer.snapshot()
            val pendingFinish = finishRequest
            if (
                cancelConnection == null &&
                pendingFinish != null &&
                !finishSent &&
                sentThroughSequence == snapshot.nextSequence - 1
            ) {
                val finishEvent =
                    buffer.finishEvent(
                        pendingFinish.capturedDurationMillis,
                        pendingFinish.clientArchiveSha256,
                    )
                if (activeConnection.send(finishEvent)) {
                    finishSent = true
                } else {
                    cancelConnection = activeConnection
                }
            }
        }
        cancelConnection?.cancel()
    }

    private fun disconnect(
        failedConnection: RealtimeConnection,
        error: IOException,
    ) {
        val schedule =
            synchronized(lock) {
                if (connection !== failedConnection || state.isTerminal()) {
                    false
                } else {
                    connection = null
                    heartbeatFuture?.cancel(false)
                    heartbeatFuture = null
                    state = ReliableRealtimeState.DISCONNECTED
                    sentThroughSequence = buffer.snapshot().lastAcknowledgedSequence
                    finishSent = false
                    if (disconnectedAtNanos == null) {
                        disconnectedAtNanos = System.nanoTime()
                    }
                    true
                }
            }
        if (!schedule) {
            return
        }
        listener.onStateChanged(ReliableRealtimeState.DISCONNECTED)
        listener.onDisconnected(error)
        scheduleReconnect()
    }

    private fun forceReconnect(activeConnection: RealtimeConnection) {
        synchronized(lock) {
            if (connection !== activeConnection || state.isTerminal()) {
                return
            }
            connection = null
            heartbeatFuture?.cancel(false)
            heartbeatFuture = null
            state = ReliableRealtimeState.DISCONNECTED
            sentThroughSequence = buffer.snapshot().lastAcknowledgedSequence
            finishSent = false
            disconnectedAtNanos = System.nanoTime()
        }
        activeConnection.cancel()
        listener.onStateChanged(ReliableRealtimeState.DISCONNECTED)
        scheduleReconnect(delayMillis = 1)
    }

    private fun scheduleReconnect(delayMillis: Long? = null) {
        val resolvedDelay =
            synchronized(lock) {
                if (state != ReliableRealtimeState.DISCONNECTED) {
                    return
                }
                val disconnectedAt = disconnectedAtNanos ?: return
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - disconnectedAt)
                if (elapsedMillis >= reconnectPolicy.reconnectWindowMillis) {
                    state = ReliableRealtimeState.FAILED
                    cancelScheduledWork()
                    buffer.close()
                    null
                } else {
                    val exponential =
                        reconnectPolicy.initialDelayMillis * (1L shl min(reconnectAttempt, MAX_BACKOFF_SHIFT))
                    reconnectAttempt++
                    min(delayMillis ?: exponential, reconnectPolicy.maximumDelayMillis)
                }
            }
        if (resolvedDelay == null) {
            scheduler.shutdown()
            listener.onStateChanged(ReliableRealtimeState.FAILED)
            return
        }
        val scheduled =
            try {
                scheduler.schedule(
                    { reconnectNow() },
                    resolvedDelay,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: RejectedExecutionException) {
                return
            }
        synchronized(lock) {
            if (state == ReliableRealtimeState.DISCONNECTED) {
                reconnectFuture = scheduled
            } else {
                scheduled.cancel(false)
            }
        }
    }

    private fun scheduleHeartbeat(intervalMillis: Long) {
        heartbeatFuture?.cancel(false)
        heartbeatFuture =
            scheduler.scheduleAtFixedRate(
                {
                    val active =
                        synchronized(lock) {
                            val activeConnection = connection
                            val sessionId = buffer.snapshot().sessionId
                            if (state == ReliableRealtimeState.READY && activeConnection != null && sessionId != null) {
                                activeConnection to sessionId
                            } else {
                                null
                            }
                        }
                    val sent =
                        active?.first?.send(
                            RealtimeHeartbeatEvent(
                                sessionId = active.second,
                                sentAtMillis = System.currentTimeMillis(),
                            ),
                        ) ?: true
                    if (!sent) {
                        active?.first?.cancel()
                    }
                },
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS,
            )
    }

    private fun failProtocol(connection: RealtimeConnection) {
        synchronized(lock) {
            if (this.connection !== connection || state.isTerminal()) {
                return
            }
            state = ReliableRealtimeState.FAILED
            cancelScheduledWork()
            buffer.close()
            this.connection = null
        }
        connection.cancel()
        scheduler.shutdownNow()
        listener.onStateChanged(ReliableRealtimeState.FAILED)
        listener.onDisconnected(VoiceAssetProtocolException("Server returned inconsistent realtime progress."))
    }

    private fun isCurrent(candidate: RealtimeConnection): Boolean =
        synchronized(lock) {
            connection === candidate && !state.isTerminal()
        }

    private fun matchesActiveSession(event: RealtimeServerEvent): Boolean {
        val actualSessionId =
            when (event) {
                is RealtimeAckEvent -> event.sessionId
                is RealtimeHeartbeatAckEvent -> event.sessionId
                is RealtimePartialTranscriptEvent -> event.sessionId
                is RealtimeFinalTranscriptEvent -> event.sessionId
                is RealtimeErrorEvent -> event.sessionId
                is RealtimeClosedEvent -> event.sessionId
                is RealtimeReadyEvent -> event.sessionId
            }
        val expectedSessionId = buffer.snapshot().sessionId
        return actualSessionId == null || expectedSessionId == null || actualSessionId == expectedSessionId
    }

    private fun cancelScheduledWork() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    private fun ReliableRealtimeState.isTerminal(): Boolean =
        this == ReliableRealtimeState.COMPLETED ||
            this == ReliableRealtimeState.FAILED ||
            this == ReliableRealtimeState.CANCELLED

    private data class FinishRequest(
        val capturedDurationMillis: Long,
        val clientArchiveSha256: String,
    )

    private companion object {
        const val DEFAULT_PENDING_BYTES = 12 * 1024 * 1024
        const val MAX_WEBSOCKET_QUEUE_BYTES = 1024 * 1024L
        const val MAX_BACKOFF_SHIFT = 10
        const val SCHEDULER_THREAD_NAME = "voiceasset-realtime-session"
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
