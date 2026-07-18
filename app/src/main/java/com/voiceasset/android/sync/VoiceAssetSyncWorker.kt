package com.voiceasset.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.voiceasset.android.VoiceAssetApplication
import com.voiceasset.android.data.StoredRecording
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.android.security.CredentialStoreException
import com.voiceasset.android.security.ServerSessionAuthenticationRequiredException
import com.voiceasset.core.api.CreateAssetRequest
import com.voiceasset.core.api.CreateUploadRequest
import com.voiceasset.core.api.SyncIdempotencyKeys
import com.voiceasset.core.api.TranscriptionRecovery
import com.voiceasset.core.api.TranscriptionResolution
import com.voiceasset.core.api.UploadRecoveryPlan
import com.voiceasset.core.api.UploadSession
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.api.VoiceAssetApiException
import com.voiceasset.core.api.VoiceAssetConnectionException
import com.voiceasset.core.api.VoiceAssetProtocolException
import com.voiceasset.core.api.VoiceAssetTlsException
import com.voiceasset.core.api.requireAndroidSyncCompatibility
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.SyncBlockReason
import com.voiceasset.core.model.SyncEvent
import com.voiceasset.core.model.SyncStage
import com.voiceasset.core.model.SyncTask
import com.voiceasset.core.model.TranscriptionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

class VoiceAssetSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val recordingSessionId =
                inputData
                    .getString(RECORDING_SESSION_ID)
                    ?.let { value -> runCatching { RecordingSessionId.parse(value) }.getOrNull() }
                    ?: return@withContext failure("invalid_work_input")
            val workMode =
                SyncWorkMode.parse(inputData.getString(WORK_MODE))
                    ?: return@withContext failure("invalid_work_input")
            val application = applicationContext as VoiceAssetApplication
            val container = application.container
            val recording =
                container.recordings.find(recordingSessionId)
                    ?: return@withContext failure("recording_not_found")
            if (recording.status != StoredRecordingStatus.SAVED || recording.recording == null) {
                return@withContext failure("recording_not_saved")
            }
            val profileId =
                recording.session.serverProfileId
                    ?: return@withContext failure("server_profile_missing")
            val profile =
                container.serverProfiles.find(profileId)
                    ?: return@withContext failure("server_profile_missing")
            var task =
                container.syncTasks.create(
                    SyncTask.create(
                        recordingSessionId = recordingSessionId,
                        serverProfileId = profile.id,
                        totalBytes = recording.recording.sizeBytes,
                        createdAtEpochMillis = nowAtLeast(recording.updatedAtEpochMillis),
                    ),
                )
            if (task.stage == SyncStage.COMPLETE) {
                return@withContext Result.success()
            }
            if (task.stage == SyncStage.FAILED) {
                return@withContext failure(task.lastErrorCode ?: "sync_failed")
            }
            if (
                workMode == SyncWorkMode.TRANSCRIPTION_ONLY &&
                task.stage !in
                setOf(
                    SyncStage.UPLOAD_COMPLETED,
                    SyncStage.TRANSCRIPTION_REQUESTED,
                    SyncStage.COMPLETE,
                )
            ) {
                return@withContext failure("transcription_not_ready")
            }

            if (task.blockReason != SyncBlockReason.NONE) {
                task = transition(task, SyncEvent.RetryStarted)
            }

            val mediaFile =
                if (workMode.runsUpload && task.stage.requiresLocalMedia()) {
                    try {
                        validateLocalMedia(recording)
                    } catch (_: LocalMediaException) {
                        task = markPermanent(task, "local_media_invalid")
                        return@withContext failure(task.lastErrorCode ?: "local_media_invalid")
                    }
                } else {
                    null
                }

            val api =
                try {
                    application.serverSessions.createApi(profile)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: ServerSessionAuthenticationRequiredException) {
                    transition(task, SyncEvent.AuthenticationRequired)
                    return@withContext failure("authentication_required")
                } catch (_: CredentialStoreException) {
                    transition(task, SyncEvent.AuthenticationRequired)
                    return@withContext failure("authentication_required")
                } catch (exception: VoiceAssetApiException) {
                    return@withContext when {
                        exception.statusCode == 401 -> {
                            transition(task, SyncEvent.AuthenticationRequired)
                            failure("authentication_required")
                        }
                        exception.statusCode == 429 || exception.statusCode >= 500 ->
                            retryOrFail(task, exception.code)
                        else -> {
                            markPermanent(task, exception.code)
                            failure(exception.code)
                        }
                    }
                } catch (_: VoiceAssetConnectionException) {
                    return@withContext retryOrFail(task, "network_unavailable")
                } catch (_: VoiceAssetTlsException) {
                    markPermanent(task, "tls_verification_failed")
                    return@withContext failure("tls_verification_failed")
                } catch (_: VoiceAssetProtocolException) {
                    markPermanent(task, "protocol_mismatch")
                    return@withContext failure("protocol_mismatch")
                } catch (_: IllegalArgumentException) {
                    task = markPermanent(task, "tls_configuration_invalid")
                    return@withContext failure(task.lastErrorCode ?: "tls_configuration_invalid")
                }

            try {
                api.getCapabilities().requireAndroidSyncCompatibility()
                task = synchronize(api, profile, recording, mediaFile, task, workMode)
                resultFor(workMode, task)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: VoiceAssetApiException) {
                when {
                    exception.statusCode == 401 -> {
                        transition(task, SyncEvent.AuthenticationRequired)
                        failure("authentication_required")
                    }
                    exception.statusCode == 429 || exception.statusCode >= 500 ->
                        retryOrFail(task, exception.code)
                    else -> {
                        markPermanent(task, exception.code)
                        failure(exception.code)
                    }
                }
            } catch (_: VoiceAssetConnectionException) {
                retryOrFail(task, "network_unavailable")
            } catch (_: VoiceAssetTlsException) {
                markPermanent(task, "tls_verification_failed")
                failure("tls_verification_failed")
            } catch (_: VoiceAssetProtocolException) {
                markPermanent(task, "protocol_mismatch")
                failure("protocol_mismatch")
            } catch (_: IllegalArgumentException) {
                markPermanent(task, "protocol_mismatch")
                failure("protocol_mismatch")
            } catch (_: IOException) {
                markPermanent(task, "local_media_io")
                failure("local_media_io")
            }
        }

    private suspend fun synchronize(
        api: VoiceAssetApi,
        profile: ServerProfile,
        recording: StoredRecording,
        mediaFile: File?,
        initialTask: SyncTask,
        workMode: SyncWorkMode,
    ): SyncTask {
        var task = initialTask
        val idempotencyKeys =
            SyncIdempotencyKeys.forRecording(
                recordingSessionId = recording.session.id,
                transcriptionGeneration = task.manualRetryGeneration,
            )
        if (task.stage == SyncStage.QUEUED) {
            val title =
                recording.session.fileName
                    .substringBeforeLast('.')
                    .ifBlank { "Voice recording" }
            val asset =
                api.createAsset(
                    CreateAssetRequest(title = title, language = "und"),
                    idempotencyKey = idempotencyKeys.asset,
                )
            task = transition(task, SyncEvent.AssetCreated(asset.id))
        }
        if (task.stage == SyncStage.ASSET_CREATED) {
            val upload =
                api.createUpload(
                    CreateUploadRequest(
                        assetId = requireNotNull(task.assetId),
                        filename = recording.session.fileName,
                        mimeType = ANDROID_RECORDING_MIME_TYPE,
                        sizeBytes = recording.recording!!.sizeBytes,
                        sha256 = recording.recording.sha256,
                    ),
                    idempotencyKey = idempotencyKeys.upload,
                )
            validateUpload(upload, task, recording)
            task = transition(task, SyncEvent.UploadCreated(upload.id))
        }
        if (task.stage == SyncStage.UPLOAD_CREATED || task.stage == SyncStage.UPLOADING) {
            val upload = api.getUpload(requireNotNull(task.uploadId))
            validateUpload(upload, task, recording)
            if (upload.state == "completed") {
                task = transition(task, SyncEvent.UploadCompleted)
            } else {
                if (upload.state != "active") {
                    throw VoiceAssetProtocolException("Upload session is not resumable.")
                }
                task = uploadMissingParts(api, requireNotNull(mediaFile), upload, task)
                val completed = api.completeUpload(upload.id)
                validateUpload(completed, task, recording)
                if (completed.state != "completed") {
                    throw VoiceAssetProtocolException("Server did not complete the upload session.")
                }
                task = transition(task, SyncEvent.UploadCompleted)
            }
        }
        val shouldRequestTranscription =
            workMode == SyncWorkMode.TRANSCRIPTION_ONLY ||
                (
                    workMode == SyncWorkMode.FULL &&
                        recording.effectiveTranscriptionPolicy(profile).autoQueuesAfterUpload()
                )
        if (task.stage == SyncStage.UPLOAD_COMPLETED && shouldRequestTranscription) {
            val job =
                api.createTranscription(
                    assetId = requireNotNull(task.assetId),
                    idempotencyKey = idempotencyKeys.transcription,
                )
            task = transition(task, SyncEvent.TranscriptionRequested(job.id))
        }
        val shouldCompleteWithoutTranscription =
            workMode == SyncWorkMode.UPLOAD_AND_COMPLETE ||
                (
                    workMode == SyncWorkMode.FULL &&
                        !recording.effectiveTranscriptionPolicy(profile).autoQueuesAfterUpload()
                )
        if (task.stage == SyncStage.UPLOAD_COMPLETED && shouldCompleteWithoutTranscription) {
            task = transition(task, SyncEvent.Completed)
        }
        if (task.stage == SyncStage.TRANSCRIPTION_REQUESTED && workMode.runsTranscription) {
            task = resolveTranscription(api, recording, task)
        }
        return task
    }

    private fun resultFor(
        workMode: SyncWorkMode,
        task: SyncTask,
    ): Result =
        when {
            task.stage == SyncStage.COMPLETE ->
                if (task.transcriptionJobId == null) {
                    Result.success(workDataOf(ASSET_ID to requireNotNull(task.assetId)))
                } else {
                    Result.success(
                        workDataOf(
                            ASSET_ID to requireNotNull(task.assetId),
                            TRANSCRIPTION_JOB_ID to task.transcriptionJobId,
                        ),
                    )
                }
            task.stage == SyncStage.FAILED -> failure(task.lastErrorCode ?: "sync_failed")
            workMode == SyncWorkMode.UPLOAD_ONLY &&
                task.stage in setOf(SyncStage.UPLOAD_COMPLETED, SyncStage.TRANSCRIPTION_REQUESTED) ->
                Result.success(workDataOf(ASSET_ID to task.assetId))
            workMode.runsTranscription && task.stage == SyncStage.TRANSCRIPTION_REQUESTED -> Result.retry()
            else -> failure("sync_incomplete")
        }

    private suspend fun resolveTranscription(
        api: VoiceAssetApi,
        recording: StoredRecording,
        initialTask: SyncTask,
    ): SyncTask {
        val jobId = requireNotNull(initialTask.transcriptionJobId)
        val assetId = requireNotNull(initialTask.assetId)
        val job = api.getTranscriptionJob(jobId)
        val fallbackRevisionId =
            if (job.state == "succeeded" && job.resultRevisionId == null) {
                api
                    .listAssetTranscripts(assetId)
                    .items
                    .singleOrNull { transcript -> transcript.assetId == assetId }
                    ?.latestRevisionId
            } else {
                null
            }
        val resolution =
            TranscriptionRecovery.resolve(
                job = job,
                expectedJobId = jobId,
                expectedAssetId = assetId,
                fallbackRevisionId = fallbackRevisionId,
            )
        if (resolution is TranscriptionResolution.Pending) {
            return initialTask
        }
        if (resolution is TranscriptionResolution.Failed) {
            return markPermanent(initialTask, resolution.errorCode)
        }
        val revisionId = (resolution as TranscriptionResolution.Ready).revisionId
        val transcript =
            TranscriptionRecovery.toLocalTranscript(
                revision = api.getTranscriptRevision(revisionId),
                recordingSessionId = recording.session.id,
                expectedRevisionId = revisionId,
                expectedAssetId = assetId,
                expectedJobId = jobId,
                cachedAtEpochMillis = nowAtLeast(recording.updatedAtEpochMillis),
            )
        (applicationContext as VoiceAssetApplication).container.transcripts.save(transcript)
        return transition(initialTask, SyncEvent.Completed)
    }

    private suspend fun uploadMissingParts(
        api: VoiceAssetApi,
        mediaFile: File,
        upload: UploadSession,
        initialTask: SyncTask,
    ): SyncTask {
        var task = initialTask
        var confirmedBytes = upload.parts.orEmpty().sumOf { part -> part.sizeBytes }
        RandomAccessFile(mediaFile, "r").use { file ->
            val plan =
                UploadRecoveryPlan.from(upload) { offset, size ->
                    file.readPart(offset, size).sha256()
                }
            plan.missingPartNumbers.forEach { partNumber ->
                val offset = (partNumber - 1L) * upload.partSize
                val remaining = upload.expectedSize - offset
                val size = minOf(upload.partSize.toLong(), remaining).toInt()
                val bytes = file.readPart(offset, size)
                val checksum = bytes.sha256()
                val stored = api.putUploadPart(upload.id, partNumber, bytes, checksum)
                if (stored.number != partNumber || stored.sizeBytes != size.toLong() || stored.sha256 != checksum) {
                    throw VoiceAssetProtocolException("Server returned inconsistent upload part metadata.")
                }
                confirmedBytes += size
                task = transition(task, SyncEvent.UploadProgress(maxOf(task.uploadedBytes, confirmedBytes)))
            }
        }
        return task
    }

    private fun validateUpload(
        upload: UploadSession,
        task: SyncTask,
        recording: StoredRecording,
    ) {
        val localRecording = requireNotNull(recording.recording)
        val expectedUploadId = task.uploadId
        if ((expectedUploadId != null && upload.id != expectedUploadId) ||
            upload.assetId != task.assetId ||
            upload.filename != recording.session.fileName ||
            upload.mimeType != ANDROID_RECORDING_MIME_TYPE ||
            upload.expectedSize != localRecording.sizeBytes ||
            upload.expectedSha256 != localRecording.sha256
        ) {
            throw VoiceAssetProtocolException("Upload session does not match the local recording.")
        }
    }

    private fun validateLocalMedia(recording: StoredRecording): File {
        val root = File(applicationContext.filesDir, RECORDING_DIRECTORY)
        val file = File(root, recording.session.fileName)
        val rootPath = root.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        if (!filePath.startsWith(rootPath) || !file.isFile) {
            throw LocalMediaException()
        }
        val metadata = requireNotNull(recording.recording)
        if (file.length() != metadata.sizeBytes || file.sha256() != metadata.sha256) {
            throw LocalMediaException()
        }
        return file
    }

    private suspend fun transition(
        task: SyncTask,
        event: SyncEvent,
    ): SyncTask =
        (applicationContext as VoiceAssetApplication).container.syncTasks.transition(
            recordingSessionId = task.recordingSessionId,
            event = event,
            updatedAtEpochMillis = nowAtLeast(task.updatedAtEpochMillis),
        )

    private suspend fun markPermanent(
        task: SyncTask,
        code: String,
    ): SyncTask = transition(task, SyncEvent.PermanentFailure(code.safeErrorCode()))

    private suspend fun retryOrFail(
        task: SyncTask,
        code: String,
    ): Result {
        if (task.attemptCount >= MAX_FAILURE_ATTEMPTS - 1) {
            markPermanent(task, "retry_exhausted")
            return failure("retry_exhausted")
        }
        transition(task, SyncEvent.RetryableFailure(code.safeErrorCode()))
        return Result.retry()
    }

    private fun failure(code: String): Result = Result.failure(workDataOf(ERROR_CODE to code.safeErrorCode()))

    private fun nowAtLeast(previous: Long): Long = maxOf(System.currentTimeMillis(), previous)

    companion object {
        const val RECORDING_SESSION_ID = "recording_session_id"
        const val WORK_MODE = "work_mode"
        const val MODE_UPLOAD_ONLY = "upload_only"
        const val MODE_UPLOAD_AND_COMPLETE = "upload_and_complete"
        const val MODE_TRANSCRIPTION_ONLY = "transcription_only"
        const val ERROR_CODE = "error_code"
        const val ASSET_ID = "asset_id"
        const val TRANSCRIPTION_JOB_ID = "transcription_job_id"
        private const val RECORDING_DIRECTORY = "recordings"
        private const val ANDROID_RECORDING_MIME_TYPE = "audio/mp4"
        private const val MAX_FAILURE_ATTEMPTS = 10
    }
}

private enum class SyncWorkMode(
    val encodedValue: String,
    val runsUpload: Boolean,
    val runsTranscription: Boolean,
) {
    FULL("full", runsUpload = true, runsTranscription = true),
    UPLOAD_ONLY(VoiceAssetSyncWorker.MODE_UPLOAD_ONLY, runsUpload = true, runsTranscription = false),
    UPLOAD_AND_COMPLETE(
        VoiceAssetSyncWorker.MODE_UPLOAD_AND_COMPLETE,
        runsUpload = true,
        runsTranscription = false,
    ),
    TRANSCRIPTION_ONLY(
        VoiceAssetSyncWorker.MODE_TRANSCRIPTION_ONLY,
        runsUpload = false,
        runsTranscription = true,
    ),
    ;

    companion object {
        fun parse(value: String?): SyncWorkMode? =
            if (value == null) {
                FULL
            } else {
                entries.singleOrNull { mode -> mode.encodedValue == value }
            }
    }
}

private fun SyncStage.requiresLocalMedia(): Boolean =
    this in
        setOf(
            SyncStage.QUEUED,
            SyncStage.ASSET_CREATED,
            SyncStage.UPLOAD_CREATED,
            SyncStage.UPLOADING,
        )

private fun TranscriptionPolicy.autoQueuesAfterUpload(): Boolean =
    this == TranscriptionPolicy.AFTER_UPLOAD ||
        this == TranscriptionPolicy.WIFI_ONLY ||
        this == TranscriptionPolicy.CHARGING_AND_WIFI

private fun StoredRecording.effectiveTranscriptionPolicy(profile: ServerProfile): TranscriptionPolicy =
    session.transcriptionPolicyOverride ?: profile.defaultTranscriptionPolicy

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun RandomAccessFile.readPart(
    offset: Long,
    size: Int,
): ByteArray {
    require(offset >= 0 && size > 0) { "upload part range is invalid" }
    val bytes = ByteArray(size)
    seek(offset)
    readFully(bytes)
    return bytes
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun String.safeErrorCode(): String =
    lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .take(100)
        .takeIf { value -> value.matches(Regex("^[a-z][a-z0-9_]{0,99}$")) }
        ?: "sync_error"

private class LocalMediaException : IOException("local recording is missing or changed")
