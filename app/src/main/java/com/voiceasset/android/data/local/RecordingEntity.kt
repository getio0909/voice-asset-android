package com.voiceasset.android.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voiceasset.android.data.StoredRecording
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingErrorCode
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy

@Entity(
    tableName = "recordings",
    indices = [
        Index(value = ["status", "updated_at_epoch_millis"]),
        Index(value = ["server_profile_id"]),
    ],
)
data class RecordingEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "server_profile_id")
    val serverProfileId: String?,
    @ColumnInfo(name = "upload_policy_override")
    val uploadPolicyOverride: String?,
    @ColumnInfo(name = "transcription_policy_override")
    val transcriptionPolicyOverride: String?,
    val status: String,
    @ColumnInfo(name = "duration_millis")
    val durationMillis: Long?,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long?,
    val sha256: String?,
    @ColumnInfo(name = "stopped_at_epoch_millis")
    val stoppedAtEpochMillis: Long?,
    @ColumnInfo(name = "error_code")
    val errorCode: String?,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

internal fun RecordingEntity.toDomain(): StoredRecording {
    val session =
        RecordingSession(
            id = RecordingSessionId.parse(sessionId),
            fileName = fileName,
            startedAtEpochMillis = startedAtEpochMillis,
            serverProfileId = serverProfileId?.let(ServerProfileId::parse),
            uploadPolicyOverride = uploadPolicyOverride?.let(UploadPolicy::valueOf),
            transcriptionPolicyOverride = transcriptionPolicyOverride?.let(TranscriptionPolicy::valueOf),
        )
    val storedStatus = StoredRecordingStatus.valueOf(status)
    val recording =
        if (storedStatus == StoredRecordingStatus.SAVED) {
            LocalRecording(
                sessionId = session.id,
                fileName = fileName,
                durationMillis = requireNotNull(durationMillis),
                sizeBytes = requireNotNull(sizeBytes),
                sha256 = requireNotNull(sha256),
                stoppedAtEpochMillis = requireNotNull(stoppedAtEpochMillis),
            )
        } else {
            null
        }

    return StoredRecording(
        session = session,
        status = storedStatus,
        recording = recording,
        errorCode = errorCode?.let(RecordingErrorCode::valueOf),
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}

internal fun RecordingSession.toEntity(updatedAtEpochMillis: Long): RecordingEntity =
    RecordingEntity(
        sessionId = id.value,
        fileName = fileName,
        startedAtEpochMillis = startedAtEpochMillis,
        serverProfileId = serverProfileId?.value,
        uploadPolicyOverride = uploadPolicyOverride?.name,
        transcriptionPolicyOverride = transcriptionPolicyOverride?.name,
        status = StoredRecordingStatus.STARTING.name,
        durationMillis = null,
        sizeBytes = null,
        sha256 = null,
        stoppedAtEpochMillis = null,
        errorCode = null,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
