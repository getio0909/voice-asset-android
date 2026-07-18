package com.voiceasset.core.model

enum class SyncStage {
    QUEUED,
    ASSET_CREATED,
    UPLOAD_CREATED,
    UPLOADING,
    UPLOAD_COMPLETED,
    TRANSCRIPTION_REQUESTED,
    COMPLETE,
    FAILED,
}

enum class SyncBlockReason {
    NONE,
    RETRY_BACKOFF,
    AUTH_REQUIRED,
}

@ConsistentCopyVisibility
data class SyncTask internal constructor(
    val recordingSessionId: RecordingSessionId,
    val serverProfileId: ServerProfileId,
    val stage: SyncStage,
    val assetId: String?,
    val uploadId: String?,
    val transcriptionJobId: String?,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val attemptCount: Int,
    val lastErrorCode: String?,
    val blockReason: SyncBlockReason,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val manualRetryGeneration: Int,
) {
    init {
        require(totalBytes > 0) { "sync total bytes must be positive" }
        require(uploadedBytes in 0..totalBytes) { "sync uploaded bytes must be within the declared total" }
        require(attemptCount >= 0) { "sync attempt count must not be negative" }
        require(manualRetryGeneration >= 0) { "manual retry generation must not be negative" }
        require(createdAtEpochMillis >= 0) { "sync creation timestamp must not be negative" }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "sync update timestamp must not precede creation"
        }
        assetId?.let { parseCanonicalUuid(it, "remote asset id") }
        uploadId?.let { parseCanonicalUuid(it, "remote upload id") }
        transcriptionJobId?.let { parseCanonicalUuid(it, "remote transcription job id") }
        lastErrorCode?.let(::validateSyncErrorCode)
        validateCheckpointShape()
    }

    companion object {
        fun create(
            recordingSessionId: RecordingSessionId,
            serverProfileId: ServerProfileId,
            totalBytes: Long,
            createdAtEpochMillis: Long,
        ): SyncTask =
            SyncTask(
                recordingSessionId = recordingSessionId,
                serverProfileId = serverProfileId,
                stage = SyncStage.QUEUED,
                assetId = null,
                uploadId = null,
                transcriptionJobId = null,
                uploadedBytes = 0,
                totalBytes = totalBytes,
                attemptCount = 0,
                lastErrorCode = null,
                blockReason = SyncBlockReason.NONE,
                createdAtEpochMillis = createdAtEpochMillis,
                updatedAtEpochMillis = createdAtEpochMillis,
                manualRetryGeneration = 0,
            )

        fun restore(
            recordingSessionId: RecordingSessionId,
            serverProfileId: ServerProfileId,
            stage: SyncStage,
            assetId: String?,
            uploadId: String?,
            transcriptionJobId: String?,
            uploadedBytes: Long,
            totalBytes: Long,
            attemptCount: Int,
            lastErrorCode: String?,
            blockReason: SyncBlockReason,
            createdAtEpochMillis: Long,
            updatedAtEpochMillis: Long,
            manualRetryGeneration: Int = 0,
        ): SyncTask =
            SyncTask(
                recordingSessionId = recordingSessionId,
                serverProfileId = serverProfileId,
                stage = stage,
                assetId = assetId,
                uploadId = uploadId,
                transcriptionJobId = transcriptionJobId,
                uploadedBytes = uploadedBytes,
                totalBytes = totalBytes,
                attemptCount = attemptCount,
                lastErrorCode = lastErrorCode,
                blockReason = blockReason,
                createdAtEpochMillis = createdAtEpochMillis,
                updatedAtEpochMillis = updatedAtEpochMillis,
                manualRetryGeneration = manualRetryGeneration,
            )
    }

    private fun validateCheckpointShape() {
        val hasAsset = assetId != null
        val hasUpload = uploadId != null
        when (stage) {
            SyncStage.QUEUED -> require(!hasAsset && !hasUpload && uploadedBytes == 0L)
            SyncStage.ASSET_CREATED -> require(hasAsset && !hasUpload && uploadedBytes == 0L)
            SyncStage.UPLOAD_CREATED -> require(hasAsset && hasUpload && uploadedBytes == 0L)
            SyncStage.UPLOADING -> require(hasAsset && hasUpload)
            SyncStage.UPLOAD_COMPLETED,
            SyncStage.TRANSCRIPTION_REQUESTED,
            SyncStage.COMPLETE,
            -> require(hasAsset && hasUpload && uploadedBytes == totalBytes)
            SyncStage.FAILED -> Unit
        }
        require(
            transcriptionJobId == null ||
                stage in setOf(SyncStage.TRANSCRIPTION_REQUESTED, SyncStage.COMPLETE, SyncStage.FAILED),
        ) {
            "transcription job id is not valid for the sync stage"
        }
        if (stage == SyncStage.FAILED) {
            require(lastErrorCode != null && blockReason == SyncBlockReason.NONE) {
                "failed sync tasks require an error and cannot be retry-blocked"
            }
        } else if (blockReason == SyncBlockReason.NONE) {
            require(lastErrorCode == null) { "unblocked sync tasks must not retain a transient error" }
        } else {
            require(stage != SyncStage.COMPLETE && lastErrorCode != null) {
                "blocked sync tasks require a nonterminal stage and error"
            }
        }
    }
}

sealed interface SyncEvent {
    data class AssetCreated(
        val assetId: String,
    ) : SyncEvent

    data class UploadCreated(
        val uploadId: String,
    ) : SyncEvent

    data class UploadProgress(
        val uploadedBytes: Long,
    ) : SyncEvent

    data object UploadCompleted : SyncEvent

    data class TranscriptionRequested(
        val jobId: String,
    ) : SyncEvent

    data object Completed : SyncEvent

    data class RetryableFailure(
        val code: String,
    ) : SyncEvent

    data object AuthenticationRequired : SyncEvent

    data class PermanentFailure(
        val code: String,
    ) : SyncEvent

    data object RetryStarted : SyncEvent

    data object ManualRetry : SyncEvent
}

class InvalidSyncTransition(
    task: SyncTask,
    event: SyncEvent,
    reason: String? = null,
) : IllegalStateException(
        buildString {
            append("${task.stage} does not accept ${event::class.simpleName}")
            if (reason != null) {
                append(": $reason")
            }
        },
    )

object SyncStateMachine {
    fun transition(
        task: SyncTask,
        event: SyncEvent,
        updatedAtEpochMillis: Long,
    ): SyncTask {
        if (updatedAtEpochMillis < task.updatedAtEpochMillis) {
            throw InvalidSyncTransition(task, event, "update timestamp regressed")
        }
        if (task.blockReason != SyncBlockReason.NONE && event !is SyncEvent.RetryStarted) {
            throw InvalidSyncTransition(task, event, "task is blocked until retry starts")
        }

        val updated =
            when (event) {
                is SyncEvent.AssetCreated -> task.assetCreated(event)
                is SyncEvent.UploadCreated -> task.uploadCreated(event)
                is SyncEvent.UploadProgress -> task.uploadProgress(event)
                SyncEvent.UploadCompleted -> task.uploadCompleted()
                is SyncEvent.TranscriptionRequested -> task.transcriptionRequested(event)
                SyncEvent.Completed -> task.completed()
                is SyncEvent.RetryableFailure -> task.retryableFailure(event)
                SyncEvent.AuthenticationRequired -> task.authenticationRequired()
                is SyncEvent.PermanentFailure -> task.permanentFailure(event)
                SyncEvent.RetryStarted -> task.retryStarted()
                SyncEvent.ManualRetry -> task.manualRetry()
            }
        return updated.copy(updatedAtEpochMillis = updatedAtEpochMillis)
    }
}

private fun SyncTask.assetCreated(event: SyncEvent.AssetCreated): SyncTask {
    val assetId = parseCanonicalUuid(event.assetId, "remote asset id")
    if (stage == SyncStage.ASSET_CREATED && this.assetId == assetId) {
        return this
    }
    if (stage != SyncStage.QUEUED) {
        throw InvalidSyncTransition(this, event)
    }
    return copy(stage = SyncStage.ASSET_CREATED, assetId = assetId)
}

private fun SyncTask.uploadCreated(event: SyncEvent.UploadCreated): SyncTask {
    val uploadId = parseCanonicalUuid(event.uploadId, "remote upload id")
    if (stage == SyncStage.UPLOAD_CREATED && this.uploadId == uploadId) {
        return this
    }
    if (stage != SyncStage.ASSET_CREATED) {
        throw InvalidSyncTransition(this, event)
    }
    return copy(stage = SyncStage.UPLOAD_CREATED, uploadId = uploadId)
}

private fun SyncTask.uploadProgress(event: SyncEvent.UploadProgress): SyncTask {
    if (stage !in setOf(SyncStage.UPLOAD_CREATED, SyncStage.UPLOADING)) {
        throw InvalidSyncTransition(this, event)
    }
    if (event.uploadedBytes < uploadedBytes || event.uploadedBytes > totalBytes) {
        throw InvalidSyncTransition(this, event, "upload progress must be monotonic and bounded")
    }
    return copy(stage = SyncStage.UPLOADING, uploadedBytes = event.uploadedBytes)
}

private fun SyncTask.uploadCompleted(): SyncTask {
    if (stage !in setOf(SyncStage.UPLOAD_CREATED, SyncStage.UPLOADING)) {
        throw InvalidSyncTransition(this, SyncEvent.UploadCompleted)
    }
    return copy(stage = SyncStage.UPLOAD_COMPLETED, uploadedBytes = totalBytes)
}

private fun SyncTask.transcriptionRequested(event: SyncEvent.TranscriptionRequested): SyncTask {
    if (stage != SyncStage.UPLOAD_COMPLETED) {
        throw InvalidSyncTransition(this, event)
    }
    return copy(
        stage = SyncStage.TRANSCRIPTION_REQUESTED,
        transcriptionJobId = parseCanonicalUuid(event.jobId, "remote transcription job id"),
    )
}

private fun SyncTask.completed(): SyncTask {
    if (stage !in setOf(SyncStage.UPLOAD_COMPLETED, SyncStage.TRANSCRIPTION_REQUESTED)) {
        throw InvalidSyncTransition(this, SyncEvent.Completed)
    }
    return copy(stage = SyncStage.COMPLETE)
}

private fun SyncTask.retryableFailure(event: SyncEvent.RetryableFailure): SyncTask {
    ensureNonterminal(event)
    validateSyncErrorCode(event.code)
    return copy(
        attemptCount = attemptCount + 1,
        lastErrorCode = event.code,
        blockReason = SyncBlockReason.RETRY_BACKOFF,
    )
}

private fun SyncTask.authenticationRequired(): SyncTask {
    ensureNonterminal(SyncEvent.AuthenticationRequired)
    return copy(
        attemptCount = attemptCount + 1,
        lastErrorCode = "unauthorized",
        blockReason = SyncBlockReason.AUTH_REQUIRED,
    )
}

private fun SyncTask.permanentFailure(event: SyncEvent.PermanentFailure): SyncTask {
    ensureNonterminal(event)
    validateSyncErrorCode(event.code)
    return copy(
        stage = SyncStage.FAILED,
        attemptCount = attemptCount + 1,
        lastErrorCode = event.code,
        blockReason = SyncBlockReason.NONE,
    )
}

private fun SyncTask.retryStarted(): SyncTask {
    if (blockReason == SyncBlockReason.NONE || stage in setOf(SyncStage.COMPLETE, SyncStage.FAILED)) {
        throw InvalidSyncTransition(this, SyncEvent.RetryStarted)
    }
    return copy(lastErrorCode = null, blockReason = SyncBlockReason.NONE)
}

private fun SyncTask.manualRetry(): SyncTask {
    if (stage != SyncStage.FAILED) {
        throw InvalidSyncTransition(this, SyncEvent.ManualRetry)
    }
    val resumeStage =
        when {
            transcriptionJobId != null -> SyncStage.UPLOAD_COMPLETED
            uploadId != null && uploadedBytes == totalBytes -> SyncStage.UPLOAD_COMPLETED
            uploadId != null && uploadedBytes > 0 -> SyncStage.UPLOADING
            uploadId != null -> SyncStage.UPLOAD_CREATED
            assetId != null -> SyncStage.ASSET_CREATED
            else -> SyncStage.QUEUED
        }
    return copy(
        stage = resumeStage,
        transcriptionJobId = null,
        attemptCount = 0,
        lastErrorCode = null,
        blockReason = SyncBlockReason.NONE,
        manualRetryGeneration = manualRetryGeneration + 1,
    )
}

private fun SyncTask.ensureNonterminal(event: SyncEvent) {
    if (stage in setOf(SyncStage.COMPLETE, SyncStage.FAILED)) {
        throw InvalidSyncTransition(this, event)
    }
}

private val syncErrorCode = Regex("^[a-z][a-z0-9_]{0,99}$")

private fun validateSyncErrorCode(value: String) {
    require(syncErrorCode.matches(value)) { "sync error code is invalid" }
}
