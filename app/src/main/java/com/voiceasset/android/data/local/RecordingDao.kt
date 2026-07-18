package com.voiceasset.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY started_at_epoch_millis DESC, session_id")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE session_id = :sessionId")
    suspend fun find(sessionId: String): RecordingEntity?

    @Query(
        """
        SELECT * FROM recordings
        WHERE status NOT IN ('SAVED', 'FAILED')
        ORDER BY updated_at_epoch_millis, session_id
        """,
    )
    suspend fun loadRecoverable(): List<RecordingEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(recording: RecordingEntity)

    @Query(
        """
        UPDATE recordings
        SET status = :status,
            error_code = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE session_id = :sessionId
          AND status IN (:allowedPreviousStatuses)
          AND updated_at_epoch_millis <= :updatedAtEpochMillis
        """,
    )
    suspend fun updateStatus(
        sessionId: String,
        status: String,
        allowedPreviousStatuses: List<String>,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE recordings
        SET status = 'SAVED',
            duration_millis = :durationMillis,
            size_bytes = :sizeBytes,
            sha256 = :sha256,
            stopped_at_epoch_millis = :stoppedAtEpochMillis,
            error_code = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE session_id = :sessionId
          AND file_name = :fileName
          AND status = 'STOPPING'
          AND updated_at_epoch_millis <= :updatedAtEpochMillis
        """,
    )
    suspend fun markSaved(
        sessionId: String,
        fileName: String,
        durationMillis: Long,
        sizeBytes: Long,
        sha256: String,
        stoppedAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE recordings
        SET status = 'SAVED',
            duration_millis = :durationMillis,
            size_bytes = :sizeBytes,
            sha256 = :sha256,
            stopped_at_epoch_millis = :stoppedAtEpochMillis,
            error_code = NULL,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE session_id = :sessionId
          AND file_name = :fileName
          AND status NOT IN ('SAVED', 'FAILED')
          AND started_at_epoch_millis <= :stoppedAtEpochMillis
          AND updated_at_epoch_millis <= :updatedAtEpochMillis
        """,
    )
    suspend fun recoverSaved(
        sessionId: String,
        fileName: String,
        durationMillis: Long,
        sizeBytes: Long,
        sha256: String,
        stoppedAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE recordings
        SET status = 'FAILED',
            error_code = :errorCode,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE session_id = :sessionId
          AND status NOT IN ('SAVED', 'FAILED')
          AND updated_at_epoch_millis <= :updatedAtEpochMillis
        """,
    )
    suspend fun markFailed(
        sessionId: String,
        errorCode: String,
        updatedAtEpochMillis: Long,
    ): Int
}
