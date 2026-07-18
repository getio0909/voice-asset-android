package com.voiceasset.android.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.voiceasset.android.data.RecordingStore
import com.voiceasset.core.model.RecordingSessionId

class RecordingExporter(
    private val context: Context,
    recordings: RecordingStore,
) {
    private val verifier = RecordingFileVerifier(context, recordings)

    suspend fun createShareIntent(recordingSessionId: RecordingSessionId): Intent? =
        verifier.resolve(recordingSessionId)?.let { recording ->
            val uri = RecordingFileProvider.uri(context.packageName, recording.fileName)
            Intent(Intent.ACTION_SEND).apply {
                type = recording.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, recording.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
}
