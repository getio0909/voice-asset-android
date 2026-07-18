package com.voiceasset.android.recording

import com.voiceasset.core.model.RecordingErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RealtimeCaptureCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `buffers locally archived frames before connection and submits final checksum`() {
        val engine = FakeRecordingEngine(twoFrames())
        val session = FakeRealtimeAudioSession()
        val coordinator = RealtimeCaptureCoordinator(engine, session)

        coordinator.start(temporaryFolder.root.resolve("capture.wav"), { throw AssertionError(it) }, {})
        val result = coordinator.stop()

        assertEquals(listOf("append:0", "append:1", "connect", "finish"), session.actions)
        assertEquals(listOf(0L, 20L), session.capturedAt)
        assertEquals(40L, result.archive.durationMillis)
        assertTrue(result.realtimeFinishSubmitted)
        assertEquals(result.archive.sha256, session.finishedSha256)
    }

    @Test
    fun `connection failure preserves local result and skips realtime finish`() {
        val engine = FakeRecordingEngine(twoFrames())
        val session = FakeRealtimeAudioSession(connectFailure = IllegalStateException("offline"))
        val coordinator = RealtimeCaptureCoordinator(engine, session)
        val failures = mutableListOf<String>()

        coordinator.start(
            temporaryFolder.root.resolve("fallback.wav"),
            { error -> failures += requireNotNull(error.message) },
            {},
        )
        val result = coordinator.stop()

        assertEquals(listOf("offline"), failures)
        assertFalse(result.realtimeFinishSubmitted)
        assertEquals(40L, result.archive.durationMillis)
        assertEquals(1, session.cancelCalls)
        assertFalse("finish" in session.actions)
    }

    @Test
    fun `sequence divergence disables only realtime and reports once`() {
        val engine = FakeRecordingEngine(twoFrames())
        val session = FakeRealtimeAudioSession(returnedSequenceOffset = 1)
        val coordinator = RealtimeCaptureCoordinator(engine, session)
        val failures = mutableListOf<String>()

        coordinator.start(
            temporaryFolder.root.resolve("sequence.wav"),
            { error -> failures += requireNotNull(error.message) },
            {},
        )
        val result = coordinator.stop()

        assertEquals(listOf("realtime audio sequence diverged from local capture"), failures)
        assertFalse(result.realtimeFinishSubmitted)
        assertEquals(listOf("append:0"), session.actions.filterNot { it == "cancel" })
        assertEquals(40L, result.archive.durationMillis)
    }

    @Test
    fun `capture start failure cancels network ownership and remains terminal`() {
        val engine = FakeRecordingEngine(emptyList(), startFailure = SecurityException("denied"))
        val session = FakeRealtimeAudioSession()
        val coordinator = RealtimeCaptureCoordinator(engine, session)

        assertThrows(SecurityException::class.java) {
            coordinator.start(temporaryFolder.root.resolve("denied.wav"), {}, {})
        }
        assertEquals(1, session.cancelCalls)
        assertThrows(IllegalStateException::class.java) { coordinator.stop() }
    }

    private fun twoFrames(): List<RealtimePcmFrame> =
        listOf(
            RealtimePcmFrame(0, 0, ByteArray(640) { 1 }),
            RealtimePcmFrame(1, 20, ByteArray(640) { 2 }),
        )

    private class FakeRecordingEngine(
        private val frames: List<RealtimePcmFrame>,
        private val startFailure: Exception? = null,
    ) : RealtimeRecordingEngine {
        private var active = false

        override fun start(
            outputFile: File,
            onFrame: (RealtimePcmFrame) -> Unit,
            onRealtimeUnavailable: (Throwable) -> Unit,
            onCaptureError: (RecordingErrorCode) -> Unit,
        ) {
            startFailure?.let { throw it }
            active = true
            var realtime = true
            frames.forEach { frame ->
                if (realtime) {
                    try {
                        onFrame(frame)
                    } catch (exception: Exception) {
                        realtime = false
                        onRealtimeUnavailable(exception)
                    }
                }
            }
        }

        override fun stop(): PcmWavArchiveMetadata {
            check(active)
            active = false
            return PcmWavArchiveMetadata(
                sizeBytes = 1_324,
                dataBytes = 1_280,
                durationMillis = 40,
                sha256 = "4d".repeat(32),
            )
        }

        override fun release() {
            active = false
        }
    }

    private class FakeRealtimeAudioSession(
        private val connectFailure: Exception? = null,
        private val returnedSequenceOffset: Long = 0,
    ) : RealtimeAudioSession {
        val actions = mutableListOf<String>()
        val capturedAt = mutableListOf<Long>()
        var cancelCalls = 0
        var finishedSha256: String? = null

        override fun connect() {
            actions += "connect"
            connectFailure?.let { throw it }
        }

        override fun appendPcm(
            capturedAtMillis: Long,
            pcm: ByteArray,
        ): Long {
            val sequence = actions.count { it.startsWith("append:") }.toLong()
            actions += "append:$sequence"
            capturedAt += capturedAtMillis
            return sequence + returnedSequenceOffset
        }

        override fun finish(
            capturedDurationMillis: Long,
            clientArchiveSha256: String,
        ) {
            actions += "finish"
            finishedSha256 = clientArchiveSha256
        }

        override fun cancel() {
            actions += "cancel"
            cancelCalls++
        }
    }
}
