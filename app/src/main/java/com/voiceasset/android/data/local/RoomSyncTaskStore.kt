package com.voiceasset.android.data.local

import com.voiceasset.android.data.SyncTaskStore
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.SyncEvent
import com.voiceasset.core.model.SyncTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSyncTaskStore(
    private val dao: SyncTaskDao,
) : SyncTaskStore {
    override fun observeAll(): Flow<List<SyncTask>> = dao.observeAll().map { entities -> entities.map(SyncTaskEntity::toDomain) }

    override suspend fun find(recordingSessionId: RecordingSessionId): SyncTask? = dao.find(recordingSessionId.value)?.toDomain()

    override suspend fun create(task: SyncTask): SyncTask = dao.create(task).toDomain()

    override suspend fun transition(
        recordingSessionId: RecordingSessionId,
        event: SyncEvent,
        updatedAtEpochMillis: Long,
    ): SyncTask = dao.transition(recordingSessionId.value, event, updatedAtEpochMillis).toDomain()
}
