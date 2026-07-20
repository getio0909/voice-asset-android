package com.voiceasset.android.export

import android.content.Context
import android.util.Log
import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.core.model.RecordingSessionId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal data class VerifiedRecordingFile(
    val file: File,
    val fileName: String,
    val mimeType: String,
)

internal fun interface RecordingFileResolver {
    suspend fun resolve(recordingSessionId: RecordingSessionId): VerifiedRecordingFile?
}

internal class RecordingFileVerifier(
    context: Context,
    private val recordings: RecordingStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RecordingFileResolver {
    private val filesDirectory = context.applicationContext.filesDir

    override suspend fun resolve(recordingSessionId: RecordingSessionId): VerifiedRecordingFile? =
        withContext(ioDispatcher) {
            val stored = recordings.find(recordingSessionId) ?: return@withContext reject("missing metadata")
            val recording = stored.recording ?: return@withContext reject("missing saved recording")
            if (
                stored.status != StoredRecordingStatus.SAVED ||
                recording.sessionId != recordingSessionId ||
                recording.fileName != stored.session.fileName
            ) {
                return@withContext reject("metadata state mismatch")
            }
            if (!RecordingFileProvider.isSupportedRecordingName(recording.fileName)) {
                return@withContext reject("unsupported file name")
            }
            val requestedDirectory = File(filesDirectory, RecordingFileProvider.RECORDING_DIRECTORY)
            val directory = requestedDirectory.canonicalFile
            val file = File(directory, recording.fileName).canonicalFile
            if (file.parentFile != directory) {
                return@withContext reject("recording path rejected")
            }
            if (!file.isFile) {
                return@withContext reject("recording file missing")
            }
            if (file.length() != recording.sizeBytes) {
                Log.w(TAG, "Playback verification rejected: file size mismatch")
                return@withContext null
            }
            if (!file.sha256().equals(recording.sha256, ignoreCase = true)) {
                Log.w(TAG, "Playback verification rejected: checksum mismatch")
                return@withContext null
            }
            VerifiedRecordingFile(
                file = file,
                fileName = recording.fileName,
                mimeType = RecordingFileProvider.mimeType(recording.fileName),
            )
        }

    private fun reject(reason: String): VerifiedRecordingFile? {
        Log.w(TAG, "Playback verification rejected: $reason")
        return null
    }

    private companion object {
        const val TAG = "VoiceAssetPlayback"
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
