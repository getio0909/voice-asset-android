package com.voiceasset.android.export

import android.content.Context
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
            val stored = recordings.find(recordingSessionId) ?: return@withContext null
            val recording = stored.recording ?: return@withContext null
            if (
                stored.status != StoredRecordingStatus.SAVED ||
                recording.sessionId != recordingSessionId ||
                recording.fileName != stored.session.fileName
            ) {
                return@withContext null
            }
            if (!RecordingFileProvider.isSupportedRecordingName(recording.fileName)) {
                return@withContext null
            }
            val requestedDirectory =
                File(filesDirectory, RecordingFileProvider.RECORDING_DIRECTORY).absoluteFile
            val directory = requestedDirectory.canonicalFile
            val requestedFile = File(directory, recording.fileName).absoluteFile
            val file = requestedFile.canonicalFile
            if (
                directory.path != requestedDirectory.path ||
                file.path != requestedFile.path ||
                file.parentFile != directory ||
                !file.isFile ||
                file.length() != recording.sizeBytes ||
                !file.sha256().equals(recording.sha256, ignoreCase = true)
            ) {
                return@withContext null
            }
            VerifiedRecordingFile(
                file = file,
                fileName = recording.fileName,
                mimeType = RecordingFileProvider.mimeType(recording.fileName),
            )
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
