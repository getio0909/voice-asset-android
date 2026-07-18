package com.voiceasset.core.api

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeReplayBufferTest {
    @Test
    fun `lost acknowledgement is reconciled by server next sequence`() {
        val buffer = RealtimeReplayBuffer(maxPendingBytes = 4096)
        val frames = List(3) { index -> ByteArray(640) { index.toByte() } }
        frames.forEachIndexed { index, frame ->
            assertEquals(index.toLong(), buffer.append(index * 20L, frame))
        }

        buffer.reconcileReady(ready(nextSequence = 0))
        assertEquals(listOf(0L, 1L, 2L), buffer.pendingEvents().map { it.sequence })
        buffer.acknowledge(
            RealtimeAckEvent(
                sessionId = SESSION_ID,
                acknowledgedSequence = 0,
                receivedBytes = 640,
            ),
        )
        assertEquals(listOf(1L, 2L), buffer.pendingEvents().map { it.sequence })

        buffer.reconcileReady(ready(nextSequence = 2))
        val snapshot = buffer.snapshot()
        assertEquals(1, snapshot.pendingFrames)
        assertEquals(1280L, snapshot.acknowledgedBytes)
        assertEquals(1L, snapshot.lastAcknowledgedSequence)
        assertEquals(listOf(2L), buffer.pendingEvents().map { it.sequence })
        assertEquals(1, buffer.resumeEvent().lastAcknowledgedSequence)
    }

    @Test
    fun `acknowledgement validates cumulative bytes and session`() {
        val buffer = RealtimeReplayBuffer(maxPendingBytes = 4096)
        buffer.append(0, ByteArray(640))
        buffer.reconcileReady(ready(nextSequence = 0))

        assertThrows(IllegalArgumentException::class.java) {
            buffer.acknowledge(
                RealtimeAckEvent(
                    sessionId = SESSION_ID,
                    acknowledgedSequence = 0,
                    receivedBytes = 639,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.reconcileReady(ready(nextSequence = 2))
        }
        assertEquals(1, buffer.snapshot().pendingFrames)
    }

    @Test
    fun `PCM is copied and capacity exhaustion is explicit`() {
        val buffer = RealtimeReplayBuffer(maxPendingBytes = 1280)
        val first = ByteArray(640) { 7 }
        buffer.append(0, first)
        first.fill(9)
        buffer.append(20, ByteArray(640) { 8 })
        assertThrows(RealtimeReplayCapacityException::class.java) {
            buffer.append(40, ByteArray(2))
        }

        buffer.reconcileReady(ready(nextSequence = 0))
        assertArrayEquals(ByteArray(640) { 7 }, buffer.pendingEvents().first().pcmBytes())
        val snapshot = buffer.snapshot()
        assertEquals(2, snapshot.pendingFrames)
        assertEquals(1280, snapshot.pendingBytes)
        assertEquals(1280L, snapshot.capturedBytes)
    }

    @Test
    fun `finish and close preserve explicit lifecycle`() {
        val buffer = RealtimeReplayBuffer(maxPendingBytes = 4096)
        buffer.append(0, ByteArray(640))
        buffer.reconcileReady(ready(nextSequence = 0))
        val finish = buffer.finishEvent(20, "a".repeat(64))
        assertEquals(0, finish.finalSequence)
        assertEquals(20, finish.capturedDurationMillis)

        buffer.close()
        assertTrue(buffer.snapshot().closed)
        assertEquals(0, buffer.snapshot().pendingFrames)
        assertThrows(IllegalStateException::class.java) {
            buffer.append(20, ByteArray(640))
        }
    }

    @Test
    fun `idempotent start can adopt the durable server session before resume`() {
        val buffer = RealtimeReplayBuffer(maxPendingBytes = 4096)
        buffer.append(0, ByteArray(640))
        buffer.adoptSession(SESSION_ID)

        assertEquals(SESSION_ID, buffer.snapshot().sessionId)
        assertEquals(-1L, buffer.resumeEvent().lastAcknowledgedSequence)
        assertThrows(IllegalArgumentException::class.java) {
            buffer.adoptSession("10000000-0000-4000-8000-000000000002")
        }
    }

    private fun ready(nextSequence: Long): RealtimeReadyEvent =
        RealtimeReadyEvent(
            protocolVersion = REALTIME_PROTOCOL_VERSION,
            sessionId = SESSION_ID,
            nextSequence = nextSequence,
            maxFrameBytes = 640,
            heartbeatIntervalMillis = 15_000,
            expiresAt = "2026-07-17T18:00:00Z",
        )

    private companion object {
        const val SESSION_ID = "10000000-0000-4000-8000-000000000001"
    }
}
