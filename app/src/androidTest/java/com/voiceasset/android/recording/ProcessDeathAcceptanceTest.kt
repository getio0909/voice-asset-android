package com.voiceasset.android.recording

import android.Manifest
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceasset.android.ENABLE_STARTUP_RECOVERY
import com.voiceasset.android.TEST_SETTINGS
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

/**
 * A two-phase acceptance test driven by the host workflow. The seed phase leaves a
 * recoverable archive behind; the workflow then kills the app process with adb before
 * starting the verify phase in a fresh application process.
 */
class ProcessDeathAcceptanceTest {
    @Test
    fun processDeathRecoveryPhase() =
        runBlocking {
            when (InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT)) {
                SEED_PHASE -> seedRecoverableArchive()
                VERIFY_PHASE -> verifyRecoveredArchive()
                null -> Unit
                else -> error("unknown process-death phase")
            }
        }

    private suspend fun seedRecoverableArchive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = ApplicationProvider.getApplicationContext<VoiceAssetApplication>()
        val settings = context.getSharedPreferences(TEST_SETTINGS, Context.MODE_PRIVATE)
        val recordingId = RecordingSessionId.parse(UUID.randomUUID().toString())
        val file = recordingFile(context, recordingId)
        context.deleteFile(MARKER_FILE)
        settings.edit().putBoolean(ENABLE_STARTUP_RECOVERY, true).commit()
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
        application.container.recordings.persist(
            RecordingState.Starting(session),
            System.currentTimeMillis(),
        )
        val engine = MediaRecorderEngine(context)
        val errors = mutableListOf<RecordingErrorCode>()
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
        context.openFileOutput(MARKER_FILE, Context.MODE_PRIVATE).use { output ->
            output.write(recordingId.value.toByteArray())
        }
    }

    private suspend fun verifyRecoveredArchive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = ApplicationProvider.getApplicationContext<VoiceAssetApplication>()
        val settings = context.getSharedPreferences(TEST_SETTINGS, Context.MODE_PRIVATE)
        val recordingId =
            RecordingSessionId.parse(
                context.openFileInput(MARKER_FILE).bufferedReader().use { it.readText().trim() },
            )
        val file = recordingFile(context, recordingId)
        try {
            val recovered =
                withTimeout(30_000) {
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
            context.deleteFile(MARKER_FILE)
            settings.edit().remove(ENABLE_STARTUP_RECOVERY).commit()
        }
    }

    private fun recordingFile(
        context: Context,
        recordingId: RecordingSessionId,
    ): File =
        File(
            File(context.filesDir, "recordings").apply { mkdirs() },
            "${recordingId.value}.m4a",
        )

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

    private companion object {
        const val PHASE_ARGUMENT = "process_death_phase"
        const val SEED_PHASE = "seed"
        const val VERIFY_PHASE = "verify"
        const val MARKER_FILE = ".process-death-recording"
    }
}
