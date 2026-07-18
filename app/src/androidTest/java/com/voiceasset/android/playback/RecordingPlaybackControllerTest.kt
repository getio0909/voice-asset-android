package com.voiceasset.android.playback

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.android.VoiceAssetApplication
import com.voiceasset.android.export.RecordingFileVerifier
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingPlaybackControllerTest {
    @Test
    fun verifiedRoomRecordingDrivesPlaybackStateWithoutExposingTheFile() =
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<VoiceAssetApplication>()
            val recordingId = RecordingSessionId.parse(UUID.randomUUID().toString())
            val bytes = "verified playback source".encodeToByteArray()
            val fileName = "${recordingId.value}.m4a"
            val directory = File(application.filesDir, "recordings").apply { mkdirs() }
            val file = File(directory, fileName).apply { writeBytes(bytes) }
            val session =
                RecordingSession(
                    id = recordingId,
                    fileName = fileName,
                    startedAtEpochMillis = 1,
                )
            val store = application.container.recordings
            store.persist(RecordingState.Starting(session), 1)
            store.persist(RecordingState.Recording(session), 2)
            store.persist(RecordingState.Stopping(session), 3)
            store.persist(
                RecordingState.Saved(
                    LocalRecording(
                        sessionId = recordingId,
                        fileName = fileName,
                        durationMillis = 1_000,
                        sizeBytes = bytes.size.toLong(),
                        sha256 = bytes.sha256(),
                        stoppedAtEpochMillis = 4,
                    ),
                ),
                5,
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val engine = TestPlaybackEngine()
            val focus = TestPlaybackFocus()
            val controller =
                RecordingPlaybackController(
                    scope = scope,
                    verifier = RecordingFileVerifier(application, store),
                    engineFactory = RecordingPlaybackEngineFactory { engine },
                    focusFactory = RecordingPlaybackFocusFactory { focus },
                )

            try {
                controller.play(recordingId)
                withTimeout(5_000) {
                    controller.state.first { it.status == RecordingPlaybackStatus.PREPARING }
                }
                assertEquals(file.canonicalFile, engine.file?.canonicalFile)

                engine.listener.onPrepared()
                assertEquals(RecordingPlaybackStatus.PLAYING, controller.state.value.status)
                controller.pause()
                assertEquals(RecordingPlaybackStatus.PAUSED, controller.state.value.status)
                controller.play(recordingId)
                assertEquals(RecordingPlaybackStatus.PLAYING, controller.state.value.status)
                controller.stop()
                assertEquals(RecordingPlaybackStatus.IDLE, controller.state.value.status)
                assertEquals(2, focus.requestCount)
                assertEquals(2, focus.abandonCount)
            } finally {
                controller.close()
                scope.cancel()
                file.delete()
            }
        }
}

private class TestPlaybackEngine : RecordingPlaybackEngine {
    lateinit var listener: RecordingPlaybackEngine.Listener
    var file: File? = null

    override fun prepare(
        file: File,
        listener: RecordingPlaybackEngine.Listener,
    ) {
        this.file = file
        this.listener = listener
    }

    override fun start() = Unit

    override fun pause() = Unit

    override fun release() = Unit
}

private class TestPlaybackFocus : RecordingPlaybackFocus {
    var requestCount = 0
    var abandonCount = 0

    override fun request(): Boolean {
        requestCount += 1
        return true
    }

    override fun abandon() {
        abandonCount += 1
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
