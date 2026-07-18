package com.voiceasset.core.api

import com.voiceasset.core.model.RecordingSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncIdempotencyKeysTest {
    @Test
    fun `operation keys remain stable and distinct after process restart`() {
        val recordingSessionId = RecordingSessionId.parse("10000000-0000-4000-8000-000000000001")

        val beforeRestart = SyncIdempotencyKeys.forRecording(recordingSessionId)
        val afterRestart = SyncIdempotencyKeys.forRecording(recordingSessionId)

        assertEquals(beforeRestart, afterRestart)
        assertEquals(3, setOf(beforeRestart.asset, beforeRestart.upload, beforeRestart.transcription).size)
        assertTrue(beforeRestart.asset.endsWith(recordingSessionId.value))
        assertTrue(beforeRestart.upload.endsWith(recordingSessionId.value))
        assertTrue(beforeRestart.transcription.endsWith(recordingSessionId.value))

        val manualRetry = SyncIdempotencyKeys.forRecording(recordingSessionId, transcriptionGeneration = 1)
        assertEquals(beforeRestart.asset, manualRetry.asset)
        assertEquals(beforeRestart.upload, manualRetry.upload)
        assertEquals(
            "android-transcription-${recordingSessionId.value}-retry-1",
            manualRetry.transcription,
        )
    }
}
