package com.voiceasset.android.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFileProviderTest {
    @Test
    fun acceptsOnlyFlatSupportedRecordingNames() {
        assertTrue(RecordingFileProvider.isSupportedRecordingName("recording.m4a"))
        assertTrue(RecordingFileProvider.isSupportedRecordingName("recording.WAV"))

        assertFalse(RecordingFileProvider.isSupportedRecordingName("../recording.m4a"))
        assertFalse(RecordingFileProvider.isSupportedRecordingName("folder/recording.m4a"))
        assertFalse(RecordingFileProvider.isSupportedRecordingName("recording.mp3"))
        assertFalse(RecordingFileProvider.isSupportedRecordingName(" recording.m4a"))
    }

    @Test
    fun mapsSupportedExtensionsToExplicitAudioTypes() {
        assertEquals("audio/mp4", RecordingFileProvider.mimeType("recording.m4a"))
        assertEquals("audio/wav", RecordingFileProvider.mimeType("recording.wav"))
    }
}
