package com.voiceasset.android.playback

import com.voiceasset.android.export.RecordingFileResolver
import com.voiceasset.android.export.VerifiedRecordingFile
import com.voiceasset.core.model.RecordingSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingPlaybackControllerTest {
    @Test
    fun verifiedRecordingPreparesStartsAndCompletes() {
        val engine = FakePlaybackEngine()
        val focusFactory = FakePlaybackFocusFactory()
        val controller = controller(listOf(engine), focusFactory)
        val recordingId = recordingId()

        controller.play(recordingId)
        assertEquals(RecordingPlaybackStatus.PREPARING, controller.state.value.status)
        assertEquals(recordingId.value, controller.state.value.recordingSessionId)
        assertEquals(1, focusFactory.focus.requestCount)

        engine.listener.onPrepared()
        assertEquals(RecordingPlaybackStatus.PLAYING, controller.state.value.status)
        assertEquals(1, engine.startCount)

        engine.listener.onCompletion()
        assertEquals(RecordingPlaybackStatus.IDLE, controller.state.value.status)
        assertNull(controller.state.value.recordingSessionId)
        assertEquals(1, engine.releaseCount)
        assertEquals(1, focusFactory.focus.abandonCount)
    }

    @Test
    fun unverifiableRecordingFailsClosedBeforeAudioFocus() {
        val focusFactory = FakePlaybackFocusFactory()
        val controller =
            RecordingPlaybackController(
                scope = CoroutineScope(Dispatchers.Unconfined),
                verifier = RecordingFileResolver { null },
                engineFactory = RecordingPlaybackEngineFactory { error("engine must not be created") },
                focusFactory = focusFactory,
            )
        val recordingId = recordingId()

        controller.play(recordingId)

        assertEquals(RecordingPlaybackStatus.FAILED, controller.state.value.status)
        assertEquals(recordingId.value, controller.state.value.recordingSessionId)
        assertEquals(0, focusFactory.focus.requestCount)
    }

    @Test
    fun userPauseAndResumeReacquireAudioFocus() {
        val engine = FakePlaybackEngine()
        val focusFactory = FakePlaybackFocusFactory()
        val controller = controller(listOf(engine), focusFactory)
        val recordingId = recordingId()
        controller.play(recordingId)
        engine.listener.onPrepared()

        controller.pause()
        assertEquals(RecordingPlaybackStatus.PAUSED, controller.state.value.status)
        assertEquals(1, engine.pauseCount)
        assertEquals(1, focusFactory.focus.abandonCount)

        controller.play(recordingId)
        assertEquals(RecordingPlaybackStatus.PLAYING, controller.state.value.status)
        assertEquals(2, engine.startCount)
        assertEquals(2, focusFactory.focus.requestCount)
    }

    @Test
    fun transientFocusLossBeforeAndDuringPlaybackWaitsForGain() {
        val engine = FakePlaybackEngine()
        val focusFactory = FakePlaybackFocusFactory()
        val controller = controller(listOf(engine), focusFactory)
        val recordingId = recordingId()
        controller.play(recordingId)

        focusFactory.listener(PlaybackFocusChange.LOSS_TRANSIENT)
        assertEquals(RecordingPlaybackStatus.PREPARING, controller.state.value.status)
        engine.listener.onPrepared()
        assertEquals(RecordingPlaybackStatus.PAUSED, controller.state.value.status)
        assertEquals(0, engine.startCount)

        controller.play(recordingId)
        assertEquals(RecordingPlaybackStatus.PAUSED, controller.state.value.status)
        assertEquals(0, engine.startCount)

        focusFactory.listener(PlaybackFocusChange.GAIN)
        assertEquals(RecordingPlaybackStatus.PLAYING, controller.state.value.status)
        assertEquals(1, engine.startCount)

        focusFactory.listener(PlaybackFocusChange.LOSS_TRANSIENT)
        assertEquals(RecordingPlaybackStatus.PAUSED, controller.state.value.status)
        assertEquals(1, engine.pauseCount)

        focusFactory.listener(PlaybackFocusChange.GAIN)
        assertEquals(RecordingPlaybackStatus.PLAYING, controller.state.value.status)
        assertEquals(2, engine.startCount)

        focusFactory.listener(PlaybackFocusChange.LOSS)
        assertEquals(RecordingPlaybackStatus.IDLE, controller.state.value.status)
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun newerPlaybackRequestReleasesAndIgnoresOlderEngine() {
        val firstEngine = FakePlaybackEngine()
        val secondEngine = FakePlaybackEngine()
        val focusFactory = FakePlaybackFocusFactory()
        val controller = controller(listOf(firstEngine, secondEngine), focusFactory)
        val firstRecordingId = recordingId()
        val secondRecordingId = recordingId()

        controller.play(firstRecordingId)
        controller.play(secondRecordingId)
        assertEquals(1, firstEngine.releaseCount)
        assertEquals(secondRecordingId.value, controller.state.value.recordingSessionId)

        firstEngine.listener.onPrepared()
        assertEquals(0, firstEngine.startCount)
        assertEquals(secondRecordingId.value, controller.state.value.recordingSessionId)

        secondEngine.listener.onPrepared()
        assertEquals(1, secondEngine.startCount)
        assertEquals(RecordingPlaybackStatus.PLAYING, controller.state.value.status)
    }

    @Test
    fun deniedAudioFocusFailsAndReleasesNoEngine() {
        val focusFactory = FakePlaybackFocusFactory().apply { focus.granted = false }
        val controller = controller(listOf(FakePlaybackEngine()), focusFactory)

        controller.play(recordingId())

        assertEquals(RecordingPlaybackStatus.FAILED, controller.state.value.status)
        assertEquals(1, focusFactory.focus.requestCount)
    }

    private fun controller(
        engines: List<FakePlaybackEngine>,
        focusFactory: FakePlaybackFocusFactory,
    ): RecordingPlaybackController {
        val remaining = ArrayDeque(engines)
        return RecordingPlaybackController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            verifier =
                RecordingFileResolver { recordingSessionId ->
                    VerifiedRecordingFile(
                        file = File("${recordingSessionId.value}.m4a"),
                        fileName = "${recordingSessionId.value}.m4a",
                        mimeType = "audio/mp4",
                    )
                },
            engineFactory = RecordingPlaybackEngineFactory { remaining.removeFirst() },
            focusFactory = focusFactory,
        )
    }
}

private class FakePlaybackEngine : RecordingPlaybackEngine {
    lateinit var listener: RecordingPlaybackEngine.Listener
    var startCount = 0
    var pauseCount = 0
    var releaseCount = 0

    override fun prepare(
        file: File,
        listener: RecordingPlaybackEngine.Listener,
    ) {
        this.listener = listener
    }

    override fun start() {
        startCount += 1
    }

    override fun pause() {
        pauseCount += 1
    }

    override fun release() {
        releaseCount += 1
    }
}

private class FakePlaybackFocusFactory : RecordingPlaybackFocusFactory {
    val focus = FakePlaybackFocus()
    lateinit var listener: (PlaybackFocusChange) -> Unit

    override fun create(onChange: (PlaybackFocusChange) -> Unit): RecordingPlaybackFocus {
        listener = onChange
        return focus
    }
}

private class FakePlaybackFocus : RecordingPlaybackFocus {
    var granted = true
    var requestCount = 0
    var abandonCount = 0

    override fun request(): Boolean {
        requestCount += 1
        return granted
    }

    override fun abandon() {
        abandonCount += 1
    }
}

private fun recordingId(): RecordingSessionId = RecordingSessionId.parse(UUID.randomUUID().toString())
