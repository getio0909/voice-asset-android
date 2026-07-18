package com.voiceasset.android.data

import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.SyncEvent
import com.voiceasset.core.model.SyncTask
import kotlinx.coroutines.flow.Flow

interface SyncTaskStore {
    fun observeAll(): Flow<List<SyncTask>>

    suspend fun find(recordingSessionId: RecordingSessionId): SyncTask?

    suspend fun create(task: SyncTask): SyncTask

    suspend fun transition(
        recordingSessionId: RecordingSessionId,
        event: SyncEvent,
        updatedAtEpochMillis: Long,
    ): SyncTask
}
