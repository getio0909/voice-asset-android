package com.voiceasset.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalTranscriptTest {
    @Test
    fun `creates an immutable cached transcript linked to its recording and remote job`() {
        val transcript =
            LocalTranscript.create(
                recordingSessionId = RecordingSessionId.parse(RECORDING_ID),
                revisionId = REVISION_ID,
                transcriptId = TRANSCRIPT_ID,
                assetId = ASSET_ID,
                sourceJobId = JOB_ID,
                kind = TranscriptKind.NORMALIZED,
                language = "en-US",
                text = "Offline field note",
                reviewStatus = TranscriptReviewStatus.PENDING,
                revisionCreatedAtEpochMillis = 1_000,
                cachedAtEpochMillis = 2_000,
            )

        assertEquals(RECORDING_ID, transcript.recordingSessionId.value)
        assertEquals(REVISION_ID, transcript.revisionId)
        assertEquals("Offline field note", transcript.text)
    }

    @Test
    fun `rejects malformed remote identifiers and timestamps`() {
        assertThrows(IllegalArgumentException::class.java) {
            localTranscript(revisionId = "not-a-uuid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            localTranscript(cachedAtEpochMillis = -1)
        }
    }

    private fun localTranscript(
        revisionId: String = REVISION_ID,
        cachedAtEpochMillis: Long = 2_000,
    ): LocalTranscript =
        LocalTranscript.create(
            recordingSessionId = RecordingSessionId.parse(RECORDING_ID),
            revisionId = revisionId,
            transcriptId = TRANSCRIPT_ID,
            assetId = ASSET_ID,
            sourceJobId = JOB_ID,
            kind = TranscriptKind.NORMALIZED,
            language = "en-US",
            text = "Offline field note",
            reviewStatus = TranscriptReviewStatus.PENDING,
            revisionCreatedAtEpochMillis = 1_000,
            cachedAtEpochMillis = cachedAtEpochMillis,
        )

    private companion object {
        const val RECORDING_ID = "10000000-0000-4000-8000-000000000001"
        const val ASSET_ID = "30000000-0000-4000-8000-000000000003"
        const val JOB_ID = "50000000-0000-4000-8000-000000000005"
        const val TRANSCRIPT_ID = "60000000-0000-4000-8000-000000000006"
        const val REVISION_ID = "70000000-0000-4000-8000-000000000007"
    }
}
