package com.voiceasset.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.voiceasset.core.model.SyncEvent
import com.voiceasset.core.model.SyncStateMachine
import com.voiceasset.core.model.SyncTask
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SyncTaskDao {
    @Query("SELECT * FROM sync_tasks ORDER BY created_at_epoch_millis DESC")
    abstract fun observeAll(): Flow<List<SyncTaskEntity>>

    @Query("SELECT * FROM sync_tasks WHERE recording_session_id = :recordingSessionId")
    abstract suspend fun find(recordingSessionId: String): SyncTaskEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(entity: SyncTaskEntity): Long

    @Update
    protected abstract suspend fun update(entity: SyncTaskEntity): Int

    @Transaction
    open suspend fun create(task: SyncTask): SyncTaskEntity {
        val entity = task.toEntity()
        if (insert(entity) != -1L) {
            return entity
        }
        val existing =
            requireNotNull(find(entity.recordingSessionId)) {
                "sync task conflict did not return the existing row"
            }
        require(
            existing.serverProfileId == entity.serverProfileId &&
                existing.totalBytes == entity.totalBytes,
        ) {
            "recording already has an incompatible sync task"
        }
        return existing
    }

    @Transaction
    open suspend fun transition(
        recordingSessionId: String,
        event: SyncEvent,
        updatedAtEpochMillis: Long,
    ): SyncTaskEntity {
        val existing = requireNotNull(find(recordingSessionId)) { "sync task does not exist" }
        val updated =
            SyncStateMachine
                .transition(
                    existing.toDomain(),
                    event,
                    maxOf(updatedAtEpochMillis, existing.updatedAtEpochMillis),
                ).toEntity()
        check(update(updated) == 1) { "sync task update did not affect exactly one row" }
        return updated
    }
}
