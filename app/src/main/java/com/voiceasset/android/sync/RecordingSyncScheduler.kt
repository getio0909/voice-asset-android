package com.voiceasset.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import java.util.concurrent.TimeUnit

interface RecordingSyncEnqueuer {
    fun enqueue(
        recordingSession: RecordingSession,
        profile: ServerProfile,
        force: Boolean = false,
    ): Boolean

    fun enqueueTranscription(
        recordingSession: RecordingSession,
        profile: ServerProfile,
        force: Boolean = true,
    ): Boolean
}

class RecordingSyncScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context),
) : RecordingSyncEnqueuer {
    override fun enqueue(
        recordingSession: RecordingSession,
        profile: ServerProfile,
        force: Boolean,
    ): Boolean {
        val uploadPolicy = recordingSession.uploadPolicyOverride ?: profile.defaultUploadPolicy
        val transcriptionPolicy =
            recordingSession.transcriptionPolicyOverride ?: profile.defaultTranscriptionPolicy
        if (uploadPolicy == UploadPolicy.MANUAL && !force) {
            return false
        }
        val uploadMode =
            if (transcriptionPolicy.leavesUploadPending()) {
                VoiceAssetSyncWorker.MODE_UPLOAD_ONLY
            } else {
                VoiceAssetSyncWorker.MODE_UPLOAD_AND_COMPLETE
            }
        val uploadRequest =
            request(
                recordingSessionId = recordingSession.id,
                mode = uploadMode,
                constraints = constraints(uploadPolicy),
            )
        var continuation =
            workManager
                .beginUniqueWork(
                    workName(recordingSession.id),
                    if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    uploadRequest,
                )
        if (transcriptionPolicy.autoRunsAfterUpload()) {
            continuation =
                continuation.then(
                    request(
                        recordingSessionId = recordingSession.id,
                        mode = VoiceAssetSyncWorker.MODE_TRANSCRIPTION_ONLY,
                        constraints = constraints(transcriptionPolicy),
                    ),
                )
        }
        continuation
            .then(IncrementalSyncWorker.oneTimeRequest(profile.id))
            .enqueue()
        return true
    }

    override fun enqueueTranscription(
        recordingSession: RecordingSession,
        profile: ServerProfile,
        force: Boolean,
    ): Boolean {
        val transcriptionPolicy =
            recordingSession.transcriptionPolicyOverride ?: profile.defaultTranscriptionPolicy
        if (transcriptionPolicy != TranscriptionPolicy.MANUAL) {
            return false
        }
        val request =
            request(
                recordingSessionId = recordingSession.id,
                mode = VoiceAssetSyncWorker.MODE_TRANSCRIPTION_ONLY,
                constraints = constraints(transcriptionPolicy),
            )
        workManager
            .beginUniqueWork(
                workName(recordingSession.id),
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            ).then(IncrementalSyncWorker.oneTimeRequest(profile.id))
            .enqueue()
        return true
    }

    fun cancel(recordingSessionId: RecordingSessionId) {
        workManager.cancelUniqueWork(workName(recordingSessionId))
    }

    private fun request(
        recordingSessionId: RecordingSessionId,
        mode: String,
        constraints: Constraints,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<VoiceAssetSyncWorker>()
            .setInputData(
                workDataOf(
                    VoiceAssetSyncWorker.RECORDING_SESSION_ID to recordingSessionId.value,
                    VoiceAssetSyncWorker.WORK_MODE to mode,
                ),
            ).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(workTag(recordingSessionId))
            .build()

    private fun constraints(policy: UploadPolicy): Constraints {
        val builder = Constraints.Builder()
        when (policy) {
            UploadPolicy.MANUAL,
            UploadPolicy.ANY_NETWORK,
            -> builder.setRequiredNetworkType(NetworkType.CONNECTED)
            UploadPolicy.WIFI_ONLY -> builder.setRequiredNetworkType(NetworkType.UNMETERED)
            UploadPolicy.CHARGING_AND_WIFI ->
                builder
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
        }
        return builder.build()
    }

    private fun constraints(policy: TranscriptionPolicy): Constraints {
        val builder = Constraints.Builder()
        when (policy) {
            TranscriptionPolicy.AFTER_UPLOAD,
            TranscriptionPolicy.MANUAL,
            -> builder.setRequiredNetworkType(NetworkType.CONNECTED)
            TranscriptionPolicy.WIFI_ONLY -> builder.setRequiredNetworkType(NetworkType.UNMETERED)
            TranscriptionPolicy.CHARGING_AND_WIFI ->
                builder
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
            TranscriptionPolicy.DISABLED,
            TranscriptionPolicy.REALTIME,
            -> error("transcription policy does not schedule batch work")
        }
        return builder.build()
    }

    companion object {
        private const val MIN_BACKOFF_SECONDS = 30L

        fun workName(recordingSessionId: RecordingSessionId): String = "voiceasset-sync-${recordingSessionId.value}"

        fun workTag(recordingSessionId: RecordingSessionId): String = "voiceasset-recording-${recordingSessionId.value}"
    }
}

private fun TranscriptionPolicy.autoRunsAfterUpload(): Boolean =
    this == TranscriptionPolicy.AFTER_UPLOAD ||
        this == TranscriptionPolicy.WIFI_ONLY ||
        this == TranscriptionPolicy.CHARGING_AND_WIFI

private fun TranscriptionPolicy.leavesUploadPending(): Boolean = autoRunsAfterUpload() || this == TranscriptionPolicy.MANUAL
