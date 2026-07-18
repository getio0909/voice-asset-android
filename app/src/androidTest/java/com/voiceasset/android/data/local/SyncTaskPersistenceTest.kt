package com.voiceasset.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.SyncBlockReason
import com.voiceasset.core.model.SyncEvent
import com.voiceasset.core.model.SyncStage
import com.voiceasset.core.model.SyncTask
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncTaskPersistenceTest {
    private lateinit var database: VoiceAssetDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, VoiceAssetDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun persistsDurableCheckpointsAndRetryBlocks() =
        runBlocking {
            seedSavedRecording()
            val store = RoomSyncTaskStore(database.syncTaskDao())
            val created = store.create(syncTask())
            assertEquals(created, store.create(syncTask()))

            store.transition(RECORDING_ID, SyncEvent.AssetCreated(ASSET_ID), 6)
            val blocked =
                store.transition(RECORDING_ID, SyncEvent.RetryableFailure("service_unavailable"), 7)

            assertEquals(SyncStage.ASSET_CREATED, blocked.stage)
            assertEquals(SyncBlockReason.RETRY_BACKOFF, blocked.blockReason)
            assertEquals(blocked, store.find(RECORDING_ID))
            assertEquals(listOf(blocked), store.observeAll().first())

            val resumed = store.transition(RECORDING_ID, SyncEvent.RetryStarted, 8)
            assertEquals(SyncBlockReason.NONE, resumed.blockReason)

            val failed = store.transition(RECORDING_ID, SyncEvent.PermanentFailure("retry_exhausted"), 9)
            val manuallyRetried = store.transition(RECORDING_ID, SyncEvent.ManualRetry, 10)
            assertEquals(SyncStage.ASSET_CREATED, manuallyRetried.stage)
            assertEquals(0, manuallyRetried.attemptCount)
            assertEquals(1, manuallyRetried.manualRetryGeneration)
            assertEquals(null, manuallyRetried.lastErrorCode)
            assertEquals(failed.assetId, manuallyRetried.assetId)
        }

    @Test
    fun preservesMonotonicCheckpointTimeWhenTheDeviceClockRegresses() =
        runBlocking {
            seedSavedRecording()
            val store = RoomSyncTaskStore(database.syncTaskDao())
            store.create(syncTask())
            store.transition(RECORDING_ID, SyncEvent.AssetCreated(ASSET_ID), 6)

            val updated = store.transition(RECORDING_ID, SyncEvent.UploadCreated(UPLOAD_ID), 5)

            assertEquals(SyncStage.UPLOAD_CREATED, updated.stage)
            assertEquals(6, updated.updatedAtEpochMillis)
        }

    private suspend fun seedSavedRecording() {
        val profileRepository = RoomServerProfileRepository(database.serverProfileDao())
        profileRepository.save(
            ServerProfile.create(
                id = PROFILE_ID,
                name = "Sync test",
                baseUrl = "https://example.test",
                authenticationMode = AuthenticationMode.LOCAL_SESSION,
                defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
                defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
                customCaPem = null,
                certificateFingerprint = null,
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
            ),
        )
        val recordings = RoomRecordingStore(database.recordingDao())
        val session =
            RecordingSession(
                id = RECORDING_ID,
                fileName = "recording.m4a",
                startedAtEpochMillis = 1,
                serverProfileId = PROFILE_ID,
            )
        recordings.persist(RecordingState.Starting(session), 1)
        recordings.persist(RecordingState.Recording(session), 2)
        recordings.persist(RecordingState.Stopping(session), 3)
        recordings.persist(
            RecordingState.Saved(
                LocalRecording(
                    sessionId = RECORDING_ID,
                    fileName = "recording.m4a",
                    durationMillis = 1_000,
                    sizeBytes = 100,
                    sha256 = "a".repeat(64),
                    stoppedAtEpochMillis = 3,
                ),
            ),
            4,
        )
    }

    private fun syncTask(): SyncTask =
        SyncTask.create(
            recordingSessionId = RECORDING_ID,
            serverProfileId = PROFILE_ID,
            totalBytes = 100,
            createdAtEpochMillis = 5,
        )

    private companion object {
        val RECORDING_ID = RecordingSessionId.parse("10000000-0000-4000-8000-000000000001")
        val PROFILE_ID = ServerProfileId.parse("20000000-0000-4000-8000-000000000002")
        const val ASSET_ID = "30000000-0000-4000-8000-000000000003"
        const val UPLOAD_ID = "40000000-0000-4000-8000-000000000004"
    }
}
