package com.voiceasset.android.recording

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceasset.android.VoiceAssetApplication
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.core.model.RecordingErrorCode
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ProcessRestartRecoveryTest {
    @Test
    fun finalizedM4aRecoversFromPersistedInterruptedState() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val application = ApplicationProvider.getApplicationContext<VoiceAssetApplication>()
            val recordingId = RecordingSessionId.parse(UUID.randomUUID().toString())
            val file =
                File(
                    File(context.filesDir, "recordings").apply { mkdirs() },
                    "${recordingId.value}.m4a",
                )
            try {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.RECORD_AUDIO,
                )

                val session =
                    RecordingSession(
                        id = recordingId,
                        fileName = file.name,
                        startedAtEpochMillis = System.currentTimeMillis(),
                    )
                val engine = MediaRecorderEngine(context)
                val errors = mutableListOf<RecordingErrorCode>()
                application.container.recordings.persist(
                    RecordingState.Starting(session),
                    System.currentTimeMillis(),
                )
                try {
                    engine.start(file) { error -> errors += error }
                    SystemClock.sleep(1_200)
                    engine.stop()
                } finally {
                    engine.release()
                }
                assertTrue("MediaRecorder emitted an error: $errors", errors.isEmpty())
                assertTrue("recording file is empty", file.isFile && file.length() > 0)
                application.container.recordings.persist(
                    RecordingState.Recording(session),
                    System.currentTimeMillis(),
                )

                RecordingRecovery(
                    recordingDirectory = File(context.filesDir, "recordings"),
                    recordingStore = application.container.recordings,
                ).recoverInterrupted()
                val recovered =
                    withTimeout(15_000) {
                        application.container.recordings.observeAll().first { recordings ->
                            recordings.any {
                                it.session.id == recordingId && it.status == StoredRecordingStatus.SAVED
                            }
                        }
                    }.first { it.session.id == recordingId }

                val localRecording = requireNotNull(recovered.recording)
                assertEquals(file.length(), localRecording.sizeBytes)
                assertEquals(file.sha256(), localRecording.sha256)
                assertTrue(localRecording.durationMillis > 0)
            } finally {
                file.delete()
            }
        }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
