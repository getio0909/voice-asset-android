package com.voiceasset.core.api

import com.voiceasset.core.model.LocalTranscript
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.TranscriptKind
import com.voiceasset.core.model.TranscriptReviewStatus
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.UUID

sealed interface TranscriptionResolution {
    data object Pending : TranscriptionResolution

    data class Failed(
        val errorCode: String,
    ) : TranscriptionResolution

    data class Ready(
        val revisionId: String,
    ) : TranscriptionResolution
}

object TranscriptionRecovery {
    fun resolve(
        job: TranscriptionJob,
        expectedJobId: String,
        expectedAssetId: String,
        fallbackRevisionId: String?,
    ): TranscriptionResolution =
        protocolChecked {
            requireCanonicalUuid(expectedJobId, "expected transcription job id")
            requireCanonicalUuid(expectedAssetId, "expected transcription asset id")
            require(job.id == expectedJobId) { "transcription job id does not match the checkpoint" }
            require(job.assetId == expectedAssetId && job.payload.assetId == expectedAssetId) {
                "transcription job asset does not match the checkpoint"
            }
            require(job.kind == "mock_transcribe") { "transcription job kind is not supported by Android sync" }
            when (job.state) {
                "queued",
                "running",
                "retry_wait",
                -> TranscriptionResolution.Pending
                "failed",
                "cancelled",
                ->
                    TranscriptionResolution.Failed(
                        job.lastErrorCode ?: "transcription_${job.state}",
                    )
                "succeeded" -> {
                    val revisionId =
                        job.resultRevisionId
                            ?: fallbackRevisionId
                            ?: throw IllegalArgumentException("completed transcription did not expose a revision")
                    requireCanonicalUuid(revisionId, "transcription result revision id")
                    TranscriptionResolution.Ready(revisionId)
                }
                else -> throw IllegalArgumentException("transcription job state is not recognized")
            }
        }

    fun toLocalTranscript(
        revision: TranscriptRevision,
        recordingSessionId: RecordingSessionId,
        expectedRevisionId: String,
        expectedAssetId: String,
        expectedJobId: String,
        cachedAtEpochMillis: Long,
    ): LocalTranscript =
        protocolChecked {
            requireCanonicalUuid(expectedRevisionId, "expected transcript revision id")
            requireCanonicalUuid(expectedAssetId, "expected transcript asset id")
            requireCanonicalUuid(expectedJobId, "expected transcript job id")
            require(revision.id == expectedRevisionId) { "transcript revision id does not match the job result" }
            require(revision.assetId == expectedAssetId) { "transcript revision asset does not match the job result" }
            require(revision.sourceJobId == expectedJobId) { "transcript revision source does not match the job result" }
            validateTimeline(revision)
            LocalTranscript.create(
                recordingSessionId = recordingSessionId,
                revisionId = revision.id,
                transcriptId = revision.transcriptId,
                assetId = revision.assetId,
                sourceJobId = expectedJobId,
                kind = TranscriptKind.valueOf(revision.kind.uppercase()),
                language = revision.language,
                text = revision.text,
                reviewStatus = TranscriptReviewStatus.valueOf(revision.reviewStatus.uppercase()),
                revisionCreatedAtEpochMillis = Instant.parse(revision.createdAt).toEpochMilli(),
                cachedAtEpochMillis = cachedAtEpochMillis,
            )
        }

    private fun validateTimeline(revision: TranscriptRevision) {
        val ordinals = mutableSetOf<Int>()
        revision.segments.forEach { segment ->
            requireCanonicalUuid(segment.id, "transcript segment id")
            require(segment.ordinal >= 0 && ordinals.add(segment.ordinal)) { "transcript segment ordinal is invalid" }
            require(segment.startMillis >= 0 && segment.endMillis >= segment.startMillis) {
                "transcript segment timeline is invalid"
            }
            require(segment.confidence == null || segment.confidence in 0.0..1.0) {
                "transcript segment confidence is invalid"
            }
            segment.words.forEach { word ->
                val start = word["start_ms"]?.jsonPrimitive?.longOrNull
                val end = word["end_ms"]?.jsonPrimitive?.longOrNull
                val text = word["text"]?.jsonPrimitive?.content
                val confidence = word["confidence"]?.jsonPrimitive?.doubleOrNull
                require(start != null && end != null && start >= 0 && end >= start) {
                    "transcript word timeline is invalid"
                }
                require(text != null && confidence != null && confidence in 0.0..1.0) {
                    "transcript word content is invalid"
                }
            }
        }
    }
}

private inline fun <T> protocolChecked(block: () -> T): T =
    try {
        block()
    } catch (exception: VoiceAssetProtocolException) {
        throw exception
    } catch (exception: Exception) {
        throw VoiceAssetProtocolException("Server response does not match the VoiceAsset transcription contract.", exception)
    }

private fun requireCanonicalUuid(
    value: String,
    field: String,
) {
    val normalized =
        try {
            UUID.fromString(value).toString()
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("$field must be a UUID", exception)
        }
    require(normalized == value) { "$field must be canonical" }
}
