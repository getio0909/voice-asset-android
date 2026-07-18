package com.voiceasset.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncStateMachineTest {
    @Test
    fun `advances durable upload and transcription checkpoints`() {
        var task = task()

        task = SyncStateMachine.transition(task, SyncEvent.AssetCreated(ASSET_ID), 2)
        task = SyncStateMachine.transition(task, SyncEvent.UploadCreated(UPLOAD_ID), 3)
        task = SyncStateMachine.transition(task, SyncEvent.UploadProgress(50), 4)
        task = SyncStateMachine.transition(task, SyncEvent.UploadCompleted, 5)
        task = SyncStateMachine.transition(task, SyncEvent.TranscriptionRequested(JOB_ID), 6)
        task = SyncStateMachine.transition(task, SyncEvent.Completed, 7)

        assertEquals(SyncStage.COMPLETE, task.stage)
        assertEquals(ASSET_ID, task.assetId)
        assertEquals(UPLOAD_ID, task.uploadId)
        assertEquals(JOB_ID, task.transcriptionJobId)
        assertEquals(task.totalBytes, task.uploadedBytes)
        assertEquals(7, task.updatedAtEpochMillis)
    }

    @Test
    fun `retry and authentication blocks preserve the last durable checkpoint`() {
        var task = SyncStateMachine.transition(task(), SyncEvent.AssetCreated(ASSET_ID), 2)
        task = SyncStateMachine.transition(task, SyncEvent.RetryableFailure("service_unavailable"), 3)

        assertEquals(SyncStage.ASSET_CREATED, task.stage)
        assertEquals(SyncBlockReason.RETRY_BACKOFF, task.blockReason)
        assertEquals(1, task.attemptCount)
        assertEquals(ASSET_ID, task.assetId)

        task = SyncStateMachine.transition(task, SyncEvent.RetryStarted, 4)
        task = SyncStateMachine.transition(task, SyncEvent.AuthenticationRequired, 5)

        assertEquals(SyncStage.ASSET_CREATED, task.stage)
        assertEquals(SyncBlockReason.AUTH_REQUIRED, task.blockReason)
        assertEquals("unauthorized", task.lastErrorCode)
        assertEquals(2, task.attemptCount)

        task = SyncStateMachine.transition(task, SyncEvent.RetryStarted, 6)
        assertEquals(SyncBlockReason.NONE, task.blockReason)
        assertNull(task.lastErrorCode)
    }

    @Test
    fun `restored network interruption resumes without losing remote identifiers or progress`() {
        var interrupted = SyncStateMachine.transition(task(), SyncEvent.AssetCreated(ASSET_ID), 2)
        interrupted = SyncStateMachine.transition(interrupted, SyncEvent.UploadCreated(UPLOAD_ID), 3)
        interrupted = SyncStateMachine.transition(interrupted, SyncEvent.UploadProgress(50), 4)
        interrupted = SyncStateMachine.transition(interrupted, SyncEvent.RetryableFailure("network_unavailable"), 5)
        val restored =
            SyncTask.restore(
                recordingSessionId = interrupted.recordingSessionId,
                serverProfileId = interrupted.serverProfileId,
                stage = interrupted.stage,
                assetId = interrupted.assetId,
                uploadId = interrupted.uploadId,
                transcriptionJobId = interrupted.transcriptionJobId,
                uploadedBytes = interrupted.uploadedBytes,
                totalBytes = interrupted.totalBytes,
                attemptCount = interrupted.attemptCount,
                lastErrorCode = interrupted.lastErrorCode,
                blockReason = interrupted.blockReason,
                createdAtEpochMillis = interrupted.createdAtEpochMillis,
                updatedAtEpochMillis = interrupted.updatedAtEpochMillis,
            )

        assertThrows(InvalidSyncTransition::class.java) {
            SyncStateMachine.transition(restored, SyncEvent.UploadProgress(75), 6)
        }
        val resumed = SyncStateMachine.transition(restored, SyncEvent.RetryStarted, 6)
        val progressed = SyncStateMachine.transition(resumed, SyncEvent.UploadProgress(75), 7)

        assertEquals(SyncStage.UPLOADING, progressed.stage)
        assertEquals(ASSET_ID, progressed.assetId)
        assertEquals(UPLOAD_ID, progressed.uploadId)
        assertEquals(75, progressed.uploadedBytes)
        assertEquals(1, progressed.attemptCount)
    }

    @Test
    fun `rejects regressions mismatched identifiers and progress overflow`() {
        val withAsset = SyncStateMachine.transition(task(), SyncEvent.AssetCreated(ASSET_ID), 2)
        val withUpload = SyncStateMachine.transition(withAsset, SyncEvent.UploadCreated(UPLOAD_ID), 3)
        val progressed = SyncStateMachine.transition(withUpload, SyncEvent.UploadProgress(50), 4)

        assertThrows(InvalidSyncTransition::class.java) {
            SyncStateMachine.transition(progressed, SyncEvent.UploadProgress(49), 5)
        }
        assertThrows(InvalidSyncTransition::class.java) {
            SyncStateMachine.transition(progressed, SyncEvent.UploadProgress(101), 5)
        }
        assertThrows(InvalidSyncTransition::class.java) {
            SyncStateMachine.transition(progressed, SyncEvent.AssetCreated(OTHER_ID), 5)
        }
        assertThrows(InvalidSyncTransition::class.java) {
            SyncStateMachine.transition(progressed, SyncEvent.UploadCompleted, 3)
        }
    }

    @Test
    fun `manual transcription may complete immediately after upload`() {
        var task = SyncStateMachine.transition(task(), SyncEvent.AssetCreated(ASSET_ID), 2)
        task = SyncStateMachine.transition(task, SyncEvent.UploadCreated(UPLOAD_ID), 3)
        task = SyncStateMachine.transition(task, SyncEvent.UploadCompleted, 4)

        task = SyncStateMachine.transition(task, SyncEvent.Completed, 5)

        assertEquals(SyncStage.COMPLETE, task.stage)
        assertNull(task.transcriptionJobId)
    }

    @Test
    fun `manual retry reconstructs a failed partial upload checkpoint`() {
        var failed = SyncStateMachine.transition(task(), SyncEvent.AssetCreated(ASSET_ID), 2)
        failed = SyncStateMachine.transition(failed, SyncEvent.UploadCreated(UPLOAD_ID), 3)
        failed = SyncStateMachine.transition(failed, SyncEvent.UploadProgress(50), 4)
        failed = SyncStateMachine.transition(failed, SyncEvent.PermanentFailure("retry_exhausted"), 5)

        val retried = SyncStateMachine.transition(failed, SyncEvent.ManualRetry, 6)

        assertEquals(SyncStage.UPLOADING, retried.stage)
        assertEquals(ASSET_ID, retried.assetId)
        assertEquals(UPLOAD_ID, retried.uploadId)
        assertEquals(50, retried.uploadedBytes)
        assertEquals(0, retried.attemptCount)
        assertNull(retried.lastErrorCode)
        assertEquals(1, retried.manualRetryGeneration)
    }

    @Test
    fun `manual retry replaces a failed transcription job generation`() {
        var failed = SyncStateMachine.transition(task(), SyncEvent.AssetCreated(ASSET_ID), 2)
        failed = SyncStateMachine.transition(failed, SyncEvent.UploadCreated(UPLOAD_ID), 3)
        failed = SyncStateMachine.transition(failed, SyncEvent.UploadCompleted, 4)
        failed = SyncStateMachine.transition(failed, SyncEvent.TranscriptionRequested(JOB_ID), 5)
        failed = SyncStateMachine.transition(failed, SyncEvent.PermanentFailure("transcription_failed"), 6)

        val retried = SyncStateMachine.transition(failed, SyncEvent.ManualRetry, 7)

        assertEquals(SyncStage.UPLOAD_COMPLETED, retried.stage)
        assertNull(retried.transcriptionJobId)
        assertEquals(retried.totalBytes, retried.uploadedBytes)
        assertEquals(1, retried.manualRetryGeneration)
    }

    private fun task(): SyncTask =
        SyncTask.create(
            recordingSessionId = RecordingSessionId.parse("10000000-0000-4000-8000-000000000001"),
            serverProfileId = ServerProfileId.parse("20000000-0000-4000-8000-000000000002"),
            totalBytes = 100,
            createdAtEpochMillis = 1,
        )

    private companion object {
        const val ASSET_ID = "30000000-0000-4000-8000-000000000003"
        const val UPLOAD_ID = "40000000-0000-4000-8000-000000000004"
        const val JOB_ID = "50000000-0000-4000-8000-000000000005"
        const val OTHER_ID = "60000000-0000-4000-8000-000000000006"
    }
}
