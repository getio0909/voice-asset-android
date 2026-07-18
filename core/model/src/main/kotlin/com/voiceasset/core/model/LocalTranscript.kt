package com.voiceasset.core.model

enum class TranscriptKind {
    RAW_ASR,
    NORMALIZED,
    LLM_CORRECTED,
    HUMAN_EDITED,
    APPROVED,
}

enum class TranscriptReviewStatus {
    PENDING,
    REVIEWED,
    APPROVED,
    REJECTED,
}

@ConsistentCopyVisibility
data class LocalTranscript private constructor(
    val recordingSessionId: RecordingSessionId,
    val revisionId: String,
    val transcriptId: String,
    val assetId: String,
    val sourceJobId: String,
    val kind: TranscriptKind,
    val language: String,
    val text: String,
    val reviewStatus: TranscriptReviewStatus,
    val revisionCreatedAtEpochMillis: Long,
    val cachedAtEpochMillis: Long,
) {
    init {
        parseCanonicalUuid(revisionId, "transcript revision id")
        parseCanonicalUuid(transcriptId, "transcript id")
        parseCanonicalUuid(assetId, "transcript asset id")
        parseCanonicalUuid(sourceJobId, "transcript source job id")
        require(LANGUAGE.matches(language)) { "transcript language is invalid" }
        require(revisionCreatedAtEpochMillis >= 0) { "transcript revision timestamp must not be negative" }
        require(cachedAtEpochMillis >= 0) { "transcript cache timestamp must not be negative" }
    }

    companion object {
        fun create(
            recordingSessionId: RecordingSessionId,
            revisionId: String,
            transcriptId: String,
            assetId: String,
            sourceJobId: String,
            kind: TranscriptKind,
            language: String,
            text: String,
            reviewStatus: TranscriptReviewStatus,
            revisionCreatedAtEpochMillis: Long,
            cachedAtEpochMillis: Long,
        ): LocalTranscript =
            LocalTranscript(
                recordingSessionId = recordingSessionId,
                revisionId = revisionId,
                transcriptId = transcriptId,
                assetId = assetId,
                sourceJobId = sourceJobId,
                kind = kind,
                language = language,
                text = text,
                reviewStatus = reviewStatus,
                revisionCreatedAtEpochMillis = revisionCreatedAtEpochMillis,
                cachedAtEpochMillis = cachedAtEpochMillis,
            )
    }
}

private val LANGUAGE = Regex("^(?:und|[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)$")
