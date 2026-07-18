package com.voiceasset.android.data

import com.voiceasset.core.model.LocalTranscript
import com.voiceasset.core.model.RecordingSessionId
import kotlinx.coroutines.flow.Flow

interface TranscriptStore {
    fun observeAll(): Flow<List<LocalTranscript>>

    suspend fun find(recordingSessionId: RecordingSessionId): LocalTranscript?

    suspend fun save(transcript: LocalTranscript)
}
