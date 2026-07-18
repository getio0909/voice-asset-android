package com.voiceasset.core.api

import com.voiceasset.core.model.RecordingSessionId
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TranscriptionRecoveryTest {
    @Test
    fun `pending jobs remain retryable without inventing a revision`() {
        val resolution =
            TranscriptionRecovery.resolve(
                job = job(state = "retry_wait", resultRevisionId = null),
                expectedJobId = JOB_ID,
                expectedAssetId = ASSET_ID,
                fallbackRevisionId = null,
            )

        assertEquals(TranscriptionResolution.Pending, resolution)
    }

    @Test
    fun `succeeded job uses its result or the contract fallback revision`() {
        val direct =
            TranscriptionRecovery.resolve(
                job = job(state = "succeeded", resultRevisionId = REVISION_ID),
                expectedJobId = JOB_ID,
                expectedAssetId = ASSET_ID,
                fallbackRevisionId = null,
            )
        val fallback =
            TranscriptionRecovery.resolve(
                job = job(state = "succeeded", resultRevisionId = null),
                expectedJobId = JOB_ID,
                expectedAssetId = ASSET_ID,
                fallbackRevisionId = REVISION_ID,
            )

        assertEquals(TranscriptionResolution.Ready(REVISION_ID), direct)
        assertEquals(TranscriptionResolution.Ready(REVISION_ID), fallback)
    }

    @Test
    fun `terminal job exposes a safe persistent error`() {
        val resolution =
            TranscriptionRecovery.resolve(
                job = job(state = "failed", resultRevisionId = null, lastErrorCode = "invalid_audio"),
                expectedJobId = JOB_ID,
                expectedAssetId = ASSET_ID,
                fallbackRevisionId = null,
            )

        assertEquals(TranscriptionResolution.Failed("invalid_audio"), resolution)
    }

    @Test
    fun `job and revision mismatches fail closed`() {
        assertThrows(VoiceAssetProtocolException::class.java) {
            TranscriptionRecovery.resolve(
                job = job(state = "succeeded", resultRevisionId = REVISION_ID).copy(assetId = OTHER_ID),
                expectedJobId = JOB_ID,
                expectedAssetId = ASSET_ID,
                fallbackRevisionId = null,
            )
        }
        assertThrows(VoiceAssetProtocolException::class.java) {
            TranscriptionRecovery.toLocalTranscript(
                revision = revision().copy(sourceJobId = OTHER_ID),
                recordingSessionId = RecordingSessionId.parse(RECORDING_ID),
                expectedRevisionId = REVISION_ID,
                expectedAssetId = ASSET_ID,
                expectedJobId = JOB_ID,
                cachedAtEpochMillis = 2_000,
            )
        }
    }

    @Test
    fun `validated revision becomes an offline local transcript`() {
        val transcript =
            TranscriptionRecovery.toLocalTranscript(
                revision = revision(),
                recordingSessionId = RecordingSessionId.parse(RECORDING_ID),
                expectedRevisionId = REVISION_ID,
                expectedAssetId = ASSET_ID,
                expectedJobId = JOB_ID,
                cachedAtEpochMillis = 2_000,
            )

        assertEquals("Offline field note", transcript.text)
        assertEquals(1_000L, transcript.revisionCreatedAtEpochMillis)
        assertEquals(2_000L, transcript.cachedAtEpochMillis)
    }

    private fun job(
        state: String,
        resultRevisionId: String?,
        lastErrorCode: String? = null,
    ): TranscriptionJob =
        TranscriptionJob(
            id = JOB_ID,
            workspaceId = WORKSPACE_ID,
            assetId = ASSET_ID,
            createdBy = USER_ID,
            kind = "mock_transcribe",
            state = state,
            payload = TranscriptionJobPayload(ASSET_ID),
            attempts = 1,
            maxAttempts = 3,
            availableAt = CREATED_AT,
            lastErrorCode = lastErrorCode,
            resultRevisionId = resultRevisionId,
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
        )

    private fun revision(): TranscriptRevision =
        TranscriptRevision(
            id = REVISION_ID,
            transcriptId = TRANSCRIPT_ID,
            assetId = ASSET_ID,
            kind = "normalized",
            language = "en-US",
            text = "Offline field note",
            providerSnapshot = JsonObject(emptyMap()),
            hotwordSnapshot = JsonObject(emptyMap()),
            glossarySnapshot = JsonObject(emptyMap()),
            diff = JsonObject(emptyMap()),
            validationResult = JsonObject(emptyMap()),
            sourceJobId = JOB_ID,
            createdByType = "system",
            reviewStatus = "pending",
            createdAt = CREATED_AT,
            segments =
                listOf(
                    TranscriptSegment(
                        id = SEGMENT_ID,
                        ordinal = 0,
                        startMillis = 0,
                        endMillis = 1_000,
                        speaker = null,
                        text = "Offline field note",
                        confidence = 0.98,
                        words = emptyList(),
                    ),
                ),
        )

    private companion object {
        const val CREATED_AT = "1970-01-01T00:00:01Z"
        const val RECORDING_ID = "10000000-0000-4000-8000-000000000001"
        const val USER_ID = "20000000-0000-4000-8000-000000000002"
        const val WORKSPACE_ID = "20000000-0000-4000-8000-000000000003"
        const val ASSET_ID = "30000000-0000-4000-8000-000000000003"
        const val JOB_ID = "50000000-0000-4000-8000-000000000005"
        const val TRANSCRIPT_ID = "60000000-0000-4000-8000-000000000006"
        const val REVISION_ID = "70000000-0000-4000-8000-000000000007"
        const val SEGMENT_ID = "80000000-0000-4000-8000-000000000008"
        const val OTHER_ID = "90000000-0000-4000-8000-000000000009"
    }
}
