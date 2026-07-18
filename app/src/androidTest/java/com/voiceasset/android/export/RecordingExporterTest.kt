package com.voiceasset.android.export

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.android.VoiceAssetApplication
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingExporterTest {
    @Test
    fun verifiedRecordingCreatesReadOnlyShareIntentAndCorruptionBlocksExport() =
        runBlocking {
            val application = ApplicationProvider.getApplicationContext<VoiceAssetApplication>()
            val recordingId = RecordingSessionId.parse(UUID.randomUUID().toString())
            val bytes = "verified recording export".encodeToByteArray()
            val fileName = "${recordingId.value}.m4a"
            val directory = File(application.filesDir, RecordingFileProvider.RECORDING_DIRECTORY).apply { mkdirs() }
            val file = File(directory, fileName).apply { writeBytes(bytes) }
            val session =
                RecordingSession(
                    id = recordingId,
                    fileName = fileName,
                    startedAtEpochMillis = 1,
                )
            val store = application.container.recordings
            store.persist(RecordingState.Starting(session), 1)
            store.persist(RecordingState.Recording(session), 2)
            store.persist(RecordingState.Stopping(session), 3)
            store.persist(
                RecordingState.Saved(
                    LocalRecording(
                        sessionId = recordingId,
                        fileName = fileName,
                        durationMillis = 1_000,
                        sizeBytes = bytes.size.toLong(),
                        sha256 = bytes.sha256(),
                        stoppedAtEpochMillis = 4,
                    ),
                ),
                5,
            )

            try {
                val exporter = RecordingExporter(application, store)
                val shareIntent = requireNotNull(exporter.createShareIntent(recordingId))
                assertEquals(Intent.ACTION_SEND, shareIntent.action)
                assertEquals("audio/mp4", shareIntent.type)
                assertTrue(shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                @Suppress("DEPRECATION")
                val uri = shareIntent.getParcelableExtra(Intent.EXTRA_STREAM) as Uri?
                assertNotNull(uri)
                assertEquals(uri, shareIntent.clipData?.getItemAt(0)?.uri)
                assertEquals(RecordingFileProvider.authority(application.packageName), uri?.authority)
                application.contentResolver.openInputStream(requireNotNull(uri)).use { input ->
                    assertArrayEquals(bytes, requireNotNull(input).readBytes())
                }
                application.contentResolver
                    .query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    ).use { cursor ->
                        requireNotNull(cursor)
                        assertTrue(cursor.moveToFirst())
                        assertEquals(fileName, cursor.getString(0))
                        assertEquals(bytes.size.toLong(), cursor.getLong(1))
                    }
                val writeAttempt =
                    runCatching {
                        application.contentResolver.openFileDescriptor(uri, "w")?.close()
                    }
                assertTrue(writeAttempt.isFailure)

                file.writeBytes(bytes + byteArrayOf(1))
                assertNull(exporter.createShareIntent(recordingId))
            } finally {
                file.delete()
            }
        }
}

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }
