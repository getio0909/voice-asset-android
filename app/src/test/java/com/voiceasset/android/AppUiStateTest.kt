package com.voiceasset.android

import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiStateTest {
    @Test
    fun initialStateIsOfflineFirstAndReadyToRecord() {
        val state = initialAppUiState()

        assertEquals(InitializationStatus.INITIALIZED, state.initializationStatus)
        assertEquals(ServerStatus.NOT_CONFIGURED, state.serverStatus)
        assertEquals(RecordingUiStatus.READY, state.recordingStatus)
        assertEquals(0, state.localRecordingCount)
        assertEquals(emptyList<LocalRecordingSummary>(), state.localRecordings)
        assertEquals(0, state.syncedAssetCount)
        assertEquals(emptyList<SyncedAssetSummary>(), state.syncedAssets)
        assertEquals(UploadPolicy.WIFI_ONLY, state.serverDraft.uploadPolicy)
        assertEquals(TranscriptionPolicy.AFTER_UPLOAD, state.serverDraft.transcriptionPolicy)
        assertEquals(null, state.recordingUploadPolicyOverride)
        assertEquals(null, state.recordingTranscriptionPolicyOverride)
    }

    @Test
    fun draftStringRepresentationRedactsPassword() {
        val draft = ServerProfileDraft(password = SecretInput.of("not-for-logs"))

        assertFalse(draft.toString().contains("not-for-logs"))
    }

    @Test
    fun offlineLibrarySearchIsBlankTolerantAndCaseInsensitive() {
        assertTrue(matchesOfflineLibrarySearch("", null, "field-note.m4a"))
        assertTrue(matchesOfflineLibrarySearch("FIELD-NOTE", "field-note.m4a", "complete"))
        assertTrue(matchesOfflineLibrarySearch("retry_exhausted", "saved", "RETRY_EXHAUSTED"))
        assertFalse(matchesOfflineLibrarySearch("meeting", "field-note.m4a", "complete"))
    }
}
