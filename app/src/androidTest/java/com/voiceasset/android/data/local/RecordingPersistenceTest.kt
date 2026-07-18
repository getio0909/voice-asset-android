package com.voiceasset.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingErrorCode
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingPersistenceTest {
    private val databaseClass = VoiceAssetDatabase::class.java
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: VoiceAssetDatabase

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "recording-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun deleteDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun recoversPausedSessionAfterDatabaseReopenAndMarksInterruption() =
        runBlocking {
            val session = testSession("21d18ca1-b076-4684-a2f2-f8228b7bd153")
            var store = RoomRecordingStore(database.recordingDao())
            store.persist(RecordingState.Starting(session), 1_000)
            store.persist(RecordingState.Recording(session), 1_100)
            store.persist(RecordingState.Pausing(session), 1_200)
            store.persist(RecordingState.Paused(session), 1_300)

            database.close()
            database = openDatabase()
            store = RoomRecordingStore(database.recordingDao())

            val recovered = store.loadRecoverable().single()
            assertEquals(session, recovered.session)
            assertEquals(StoredRecordingStatus.PAUSED, recovered.status)

            store.persist(
                RecordingState.Failed(session.id, RecordingErrorCode.CAPTURE_INTERRUPTED),
                1_400,
            )

            assertEquals(emptyList<Any>(), store.loadRecoverable())
            val failed = requireNotNull(store.find(session.id))
            assertEquals(StoredRecordingStatus.FAILED, failed.status)
            assertEquals(RecordingErrorCode.CAPTURE_INTERRUPTED, failed.errorCode)
        }

    @Test
    fun storesImmutableFileMetadataOnlyAfterStop() =
        runBlocking {
            val session = testSession("4578591b-c141-4dce-a033-c25479eb92f8")
            val store = RoomRecordingStore(database.recordingDao())
            store.persist(RecordingState.Starting(session), 1_000)
            store.persist(RecordingState.Recording(session), 1_100)
            store.persist(RecordingState.Stopping(session), 1_200)
            val recording =
                LocalRecording(
                    sessionId = session.id,
                    fileName = session.fileName,
                    durationMillis = 3_000,
                    sizeBytes = 48_000,
                    sha256 = "7b".repeat(32),
                    stoppedAtEpochMillis = 4_000,
                )

            store.persist(RecordingState.Saved(recording), 4_000)

            val saved = requireNotNull(store.find(session.id))
            assertEquals(StoredRecordingStatus.SAVED, saved.status)
            assertEquals(recording, saved.recording)
        }

    @Test
    fun promotesRepairedWavFromInterruptedCaptureExactlyOnce() =
        runBlocking {
            val id = "e1c0ccf0-40c9-4269-b25c-956bf769ae65"
            val session =
                RecordingSession(
                    id = RecordingSessionId.parse(id),
                    fileName = "$id.wav",
                    startedAtEpochMillis = 1_000,
                )
            val store = RoomRecordingStore(database.recordingDao())
            store.persist(RecordingState.Starting(session), 1_000)
            store.persist(RecordingState.Recording(session), 1_100)
            val recording =
                LocalRecording(
                    sessionId = session.id,
                    fileName = session.fileName,
                    durationMillis = 40,
                    sizeBytes = 1_324,
                    sha256 = "3c".repeat(32),
                    stoppedAtEpochMillis = 1_300,
                )

            store.recoverSaved(recording, 1_400)

            val saved = requireNotNull(store.find(session.id))
            assertEquals(StoredRecordingStatus.SAVED, saved.status)
            assertEquals(recording, saved.recording)
            val repeated = runCatching { store.recoverSaved(recording, 1_500) }
            assertTrue(repeated.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun rejectsOutOfOrderOrStalePersistentTransitions() =
        runBlocking {
            val session = testSession("a7edfcaa-5894-43c0-a8e0-19fe764e9477")
            val store = RoomRecordingStore(database.recordingDao())
            store.persist(RecordingState.Starting(session), 1_000)

            val outOfOrder = runCatching { store.persist(RecordingState.Paused(session), 1_100) }
            assertTrue(outOfOrder.exceptionOrNull() is IllegalStateException)
            store.persist(RecordingState.Recording(session), 1_200)
            val stale = runCatching { store.persist(RecordingState.Pausing(session), 1_100) }
            assertTrue(stale.exceptionOrNull() is IllegalStateException)
        }

    private fun openDatabase(): VoiceAssetDatabase = Room.databaseBuilder(context, databaseClass, databaseName).build()

    private fun testSession(id: String): RecordingSession =
        RecordingSession(
            id = RecordingSessionId.parse(id),
            fileName = "$id.m4a",
            startedAtEpochMillis = 1_000,
            uploadPolicyOverride = UploadPolicy.CHARGING_AND_WIFI,
            transcriptionPolicyOverride = TranscriptionPolicy.DISABLED,
        )
}
