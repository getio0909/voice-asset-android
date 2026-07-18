package com.voiceasset.android.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.SyncBlockReason
import com.voiceasset.core.model.SyncStage
import com.voiceasset.core.model.SyncTask

@Entity(
    tableName = "sync_tasks",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["recording_session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["server_profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["server_profile_id", "stage", "updated_at_epoch_millis"]),
    ],
)
data class SyncTaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "recording_session_id")
    val recordingSessionId: String,
    @ColumnInfo(name = "server_profile_id")
    val serverProfileId: String,
    val stage: String,
    @ColumnInfo(name = "asset_id")
    val assetId: String?,
    @ColumnInfo(name = "upload_id")
    val uploadId: String?,
    @ColumnInfo(name = "transcription_job_id")
    val transcriptionJobId: String?,
    @ColumnInfo(name = "uploaded_bytes")
    val uploadedBytes: Long,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,
    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,
    @ColumnInfo(name = "block_reason")
    val blockReason: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "manual_retry_generation", defaultValue = "0")
    val manualRetryGeneration: Int,
)

internal fun SyncTask.toEntity(): SyncTaskEntity =
    SyncTaskEntity(
        recordingSessionId = recordingSessionId.value,
        serverProfileId = serverProfileId.value,
        stage = stage.name,
        assetId = assetId,
        uploadId = uploadId,
        transcriptionJobId = transcriptionJobId,
        uploadedBytes = uploadedBytes,
        totalBytes = totalBytes,
        attemptCount = attemptCount,
        lastErrorCode = lastErrorCode,
        blockReason = blockReason.name,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        manualRetryGeneration = manualRetryGeneration,
    )

internal fun SyncTaskEntity.toDomain(): SyncTask =
    SyncTask.restore(
        recordingSessionId = RecordingSessionId.parse(recordingSessionId),
        serverProfileId = ServerProfileId.parse(serverProfileId),
        stage = SyncStage.valueOf(stage),
        assetId = assetId,
        uploadId = uploadId,
        transcriptionJobId = transcriptionJobId,
        uploadedBytes = uploadedBytes,
        totalBytes = totalBytes,
        attemptCount = attemptCount,
        lastErrorCode = lastErrorCode,
        blockReason = SyncBlockReason.valueOf(blockReason),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        manualRetryGeneration = manualRetryGeneration,
    )
