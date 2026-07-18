package com.voiceasset.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.core.model.LocalTranscript
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import com.voiceasset.core.model.TranscriptKind
import com.voiceasset.core.model.TranscriptReviewStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranscriptPersistenceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: VoiceAssetDatabase

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "transcript-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun deleteDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun cachedRevisionSurvivesDatabaseReopenAndUpsertsIdempotently() =
        runBlocking {
            val recordingId = RecordingSessionId.parse(RECORDING_ID)
            val recordingStore = RoomRecordingStore(database.recordingDao())
            val session =
                RecordingSession(
                    id = recordingId,
                    fileName = "$RECORDING_ID.m4a",
                    startedAtEpochMillis = 1,
                )
            recordingStore.persist(RecordingState.Starting(session), 1)
            var transcriptStore = RoomTranscriptStore(database.transcriptDao())
            transcriptStore.save(transcript(recordingId, "Offline field note", 2))

            database.close()
            database = openDatabase()
            transcriptStore = RoomTranscriptStore(database.transcriptDao())

            assertEquals("Offline field note", requireNotNull(transcriptStore.find(recordingId)).text)
            transcriptStore.save(transcript(recordingId, "Offline field note", 3))
            assertEquals(3, requireNotNull(transcriptStore.find(recordingId)).cachedAtEpochMillis)
        }

    private fun openDatabase(): VoiceAssetDatabase = Room.databaseBuilder(context, VoiceAssetDatabase::class.java, databaseName).build()

    private fun transcript(
        recordingSessionId: RecordingSessionId,
        text: String,
        cachedAtEpochMillis: Long,
    ): LocalTranscript =
        LocalTranscript.create(
            recordingSessionId = recordingSessionId,
            revisionId = REVISION_ID,
            transcriptId = TRANSCRIPT_ID,
            assetId = ASSET_ID,
            sourceJobId = JOB_ID,
            kind = TranscriptKind.NORMALIZED,
            language = "en-US",
            text = text,
            reviewStatus = TranscriptReviewStatus.PENDING,
            revisionCreatedAtEpochMillis = 2,
            cachedAtEpochMillis = cachedAtEpochMillis,
        )

    private companion object {
        const val RECORDING_ID = "10000000-0000-4000-8000-000000000001"
        const val ASSET_ID = "30000000-0000-4000-8000-000000000003"
        const val JOB_ID = "50000000-0000-4000-8000-000000000005"
        const val TRANSCRIPT_ID = "60000000-0000-4000-8000-000000000006"
        const val REVISION_ID = "70000000-0000-4000-8000-000000000007"
    }
}
