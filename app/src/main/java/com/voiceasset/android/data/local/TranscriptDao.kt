package com.voiceasset.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM local_transcripts ORDER BY revision_created_at_epoch_millis DESC")
    fun observeAll(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM local_transcripts WHERE recording_session_id = :recordingSessionId")
    suspend fun find(recordingSessionId: String): TranscriptEntity?

    @Upsert
    suspend fun upsert(transcript: TranscriptEntity)
}
