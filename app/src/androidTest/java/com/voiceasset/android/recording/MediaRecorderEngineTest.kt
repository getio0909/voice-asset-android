package com.voiceasset.android.recording

import android.Manifest
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceasset.core.model.RecordingErrorCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class MediaRecorderEngineTest {
    @Test
    fun capturesReadableM4aOnHostedEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )

        val outputFile =
            File(
                File(context.filesDir, "recordings").apply { mkdirs() },
                "media-recorder-${UUID.randomUUID()}.m4a",
            )
        val engine = MediaRecorderEngine(context)
        val errors = mutableListOf<RecordingErrorCode>()
        try {
            engine.start(outputFile) { error -> errors += error }
            SystemClock.sleep(1_000)
            engine.pause()
            SystemClock.sleep(250)
            engine.resume()
            SystemClock.sleep(1_000)
            engine.stop()
            engine.release()

            assertTrue("MediaRecorder emitted an error: $errors", errors.isEmpty())
            assertTrue("recording file is empty", outputFile.isFile && outputFile.length() > 0)
            assertTrue(
                "recording file has no readable duration",
                readDurationMillis(outputFile)?.let { duration -> duration > 0 } == true,
            )
        } finally {
            engine.release()
            outputFile.delete()
        }
    }

    private fun readDurationMillis(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }
}
