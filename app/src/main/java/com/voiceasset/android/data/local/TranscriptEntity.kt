package com.voiceasset.android.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voiceasset.core.model.LocalTranscript
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.TranscriptKind
import com.voiceasset.core.model.TranscriptReviewStatus

@Entity(
    tableName = "local_transcripts",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["recording_session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["revision_id"], unique = true),
        Index(value = ["asset_id"]),
    ],
)
data class TranscriptEntity(
    @PrimaryKey
    @ColumnInfo(name = "recording_session_id")
    val recordingSessionId: String,
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "transcript_id")
    val transcriptId: String,
    @ColumnInfo(name = "asset_id")
    val assetId: String,
    @ColumnInfo(name = "source_job_id")
    val sourceJobId: String,
    val kind: String,
    val language: String,
    val text: String,
    @ColumnInfo(name = "review_status")
    val reviewStatus: String,
    @ColumnInfo(name = "revision_created_at_epoch_millis")
    val revisionCreatedAtEpochMillis: Long,
    @ColumnInfo(name = "cached_at_epoch_millis")
    val cachedAtEpochMillis: Long,
)

internal fun LocalTranscript.toEntity(): TranscriptEntity =
    TranscriptEntity(
        recordingSessionId = recordingSessionId.value,
        revisionId = revisionId,
        transcriptId = transcriptId,
        assetId = assetId,
        sourceJobId = sourceJobId,
        kind = kind.name,
        language = language,
        text = text,
        reviewStatus = reviewStatus.name,
        revisionCreatedAtEpochMillis = revisionCreatedAtEpochMillis,
        cachedAtEpochMillis = cachedAtEpochMillis,
    )

internal fun TranscriptEntity.toDomain(): LocalTranscript =
    LocalTranscript.create(
        recordingSessionId = RecordingSessionId.parse(recordingSessionId),
        revisionId = revisionId,
        transcriptId = transcriptId,
        assetId = assetId,
        sourceJobId = sourceJobId,
        kind = TranscriptKind.valueOf(kind),
        language = language,
        text = text,
        reviewStatus = TranscriptReviewStatus.valueOf(reviewStatus),
        revisionCreatedAtEpochMillis = revisionCreatedAtEpochMillis,
        cachedAtEpochMillis = cachedAtEpochMillis,
    )
