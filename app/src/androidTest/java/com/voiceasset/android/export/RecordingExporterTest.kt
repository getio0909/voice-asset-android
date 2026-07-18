package com.voiceasset.android.export

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceasset.android.VoiceAssetApplication
import com.voiceasset.core.model.RecordingSessionId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingExporterTest {
    @Test
    fun verifiedRecordingCreatesReadOnlyShareIntentAndCorruptionBlocksExport() =
        runBlocking {
            val application =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .applicationContext as VoiceAssetApplication
            val recordingId = RecordingSessionId.parse(UUID.randomUUID().toString())
            val bytes = "verified recording export".encodeToByteArray()
            val fileName = "${recordingId.value}.m4a"
            val directory = File(application.filesDir, RecordingFileProvider.RECORDING_DIRECTORY).apply { mkdirs() }
            val file = File(directory, fileName).apply { writeBytes(bytes) }
            try {
                val exporter =
                    RecordingExporter(
                        application,
                        RecordingFileResolver { requestedId ->
                            if (requestedId != recordingId || !file.isFile || !file.readBytes().contentEquals(bytes)) {
                                null
                            } else {
                                VerifiedRecordingFile(file, fileName, "audio/mp4")
                            }
                        },
                    )
                val shareIntent = requireNotNull(exporter.createShareIntent(recordingId))
                assertEquals(Intent.ACTION_SEND, shareIntent.action)
                assertEquals("audio/mp4", shareIntent.type)
                assertTrue(shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                @Suppress("DEPRECATION")
                val uri = shareIntent.getParcelableExtra(Intent.EXTRA_STREAM) as Uri?
                assertTrue(uri != null)
                assertEquals(uri, shareIntent.clipData?.getItemAt(0)?.uri)
                assertEquals(RecordingFileProvider.authority(application.packageName), uri?.authority)
                assertEquals(fileName, uri?.lastPathSegment)
                assertEquals(bytes.size.toLong(), file.length())

                file.writeBytes(bytes + byteArrayOf(1))
                assertNull(exporter.createShareIntent(recordingId))
            } finally {
                file.delete()
            }
        }
}
