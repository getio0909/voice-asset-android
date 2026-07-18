package com.voiceasset.android.recording

import com.voiceasset.core.model.RecordingErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioRecordRealtimeEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `capture archives each frame before publishing it`() {
        val source = FakePcmSource(ByteArray(1280) { index -> (index % 101).toByte() }, maxReadBytes = 173)
        val engine = AudioRecordRealtimeEngine(PcmSourceFactory { source })
        val file = temporaryFolder.root.resolve("capture.wav")
        val frames = CopyOnWriteArrayList<RealtimePcmFrame>()
        val published = CountDownLatch(2)

        engine.start(
            outputFile = file,
            onFrame = { frame ->
                assertTrue(file.length() >= 44 + frame.pcm.size * (frame.sequence + 1))
                frames.add(frame)
                published.countDown()
            },
            onRealtimeUnavailable = { throw AssertionError(it) },
            onCaptureError = { throw AssertionError(it) },
        )

        assertTrue(published.await(5, TimeUnit.SECONDS))
        val metadata = engine.stop()
        assertEquals(listOf(0L, 1L), frames.map { it.sequence })
        assertEquals(listOf(0L, 20L), frames.map { it.capturedAtMillis })
        assertEquals(1280L, metadata.dataBytes)
        assertEquals(40L, metadata.durationMillis)
        assertTrue(source.released)
    }

    @Test
    fun `network callback failure disables realtime but preserves local archive`() {
        val source = FakePcmSource(ByteArray(1280) { 3 })
        val engine = AudioRecordRealtimeEngine(PcmSourceFactory { source })
        val file = temporaryFolder.root.resolve("fallback.wav")
        val publishCalls = AtomicInteger()
        val realtimeUnavailable = CountDownLatch(1)

        engine.start(
            outputFile = file,
            onFrame = {
                publishCalls.incrementAndGet()
                throw IllegalStateException("network queue unavailable")
            },
            onRealtimeUnavailable = { realtimeUnavailable.countDown() },
            onCaptureError = { throw AssertionError(it) },
        )

        assertTrue(realtimeUnavailable.await(5, TimeUnit.SECONDS))
        assertTrue(source.exhausted.await(5, TimeUnit.SECONDS))
        val metadata = engine.stop()
        assertEquals(1, publishCalls.get())
        assertEquals(1280L, metadata.dataBytes)
        assertEquals(40L, metadata.durationMillis)
    }

    @Test
    fun `capture failure is classified while retaining completed samples`() {
        val source = FakePcmSource(ByteArray(640), failureCode = -3)
        val engine = AudioRecordRealtimeEngine(PcmSourceFactory { source })
        val file = temporaryFolder.root.resolve("capture-error.wav")
        val captureError = CountDownLatch(1)

        engine.start(
            outputFile = file,
            onFrame = {},
            onRealtimeUnavailable = { throw AssertionError(it) },
            onCaptureError = { error ->
                assertEquals(RecordingErrorCode.ENGINE_FAILURE, error)
                captureError.countDown()
            },
        )

        assertTrue(captureError.await(5, TimeUnit.SECONDS))
        val metadata = engine.stop()
        assertEquals(640L, metadata.dataBytes)
        assertEquals(RecordingErrorCode.ENGINE_FAILURE, engine.captureError())
    }

    @Test
    fun `start failure removes the empty archive`() {
        val file = temporaryFolder.root.resolve("start-error.wav")
        val engine =
            AudioRecordRealtimeEngine(
                PcmSourceFactory {
                    throw SecurityException("microphone denied")
                },
            )

        assertThrows(SecurityException::class.java) {
            engine.start(file, {}, {}, {})
        }
        assertFalse(file.exists())
    }
}

private class FakePcmSource(
    private val data: ByteArray,
    private val maxReadBytes: Int = Int.MAX_VALUE,
    private val failureCode: Int? = null,
) : PcmSource {
    val exhausted = CountDownLatch(1)

    @Volatile
    var released = false

    @Volatile
    private var stopped = false
    private var offset = 0

    override fun start() = Unit

    override fun read(
        target: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (this.offset < data.size) {
            val count = minOf(length, maxReadBytes, data.size - this.offset)
            data.copyInto(target, offset, this.offset, this.offset + count)
            this.offset += count
            return count
        }
        exhausted.countDown()
        failureCode?.let { return it }
        while (!stopped) {
            Thread.yield()
        }
        return -3
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
        stopped = true
    }
}
