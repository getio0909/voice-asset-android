package com.voiceasset.android.export

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.android.VoiceAssetApplication
import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.data.StoredRecording
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
            val recording =
                LocalRecording(
                    sessionId = recordingId,
                    fileName = fileName,
                    durationMillis = 1_000,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = bytes.sha256(),
                    stoppedAtEpochMillis = 4,
                )
            val store =
                object : RecordingStore {
                    private val stored = StoredRecording(session, StoredRecordingStatus.SAVED, recording, null, 5)

                    override fun observeAll(): Flow<List<StoredRecording>> = flowOf(listOf(stored))

                    override suspend fun find(id: RecordingSessionId): StoredRecording? = stored.takeIf { it.session.id == id }

                    override suspend fun loadRecoverable(): List<StoredRecording> = emptyList()

                    override suspend fun recoverSaved(
                        recording: LocalRecording,
                        updatedAtEpochMillis: Long,
                    ) = error("not used")

                    override suspend fun persist(
                        state: RecordingState,
                        updatedAtEpochMillis: Long,
                    ) = error("not used")
                }

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
