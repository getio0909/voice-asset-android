package com.voiceasset.android.data.local

import com.voiceasset.android.data.TranscriptStore
import com.voiceasset.core.model.LocalTranscript
import com.voiceasset.core.model.RecordingSessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomTranscriptStore(
    private val dao: TranscriptDao,
) : TranscriptStore {
    override fun observeAll(): Flow<List<LocalTranscript>> =
        dao.observeAll().map { transcripts -> transcripts.map(TranscriptEntity::toDomain) }

    override suspend fun find(recordingSessionId: RecordingSessionId): LocalTranscript? = dao.find(recordingSessionId.value)?.toDomain()

    override suspend fun save(transcript: LocalTranscript) {
        dao.upsert(transcript.toEntity())
    }
}
