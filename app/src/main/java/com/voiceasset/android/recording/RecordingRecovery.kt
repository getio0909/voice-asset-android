package com.voiceasset.android.recording

import android.media.MediaMetadataRetriever
import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.data.StoredRecording
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingErrorCode
import com.voiceasset.core.model.RecordingState
import java.io.File
import java.security.MessageDigest

internal class RecordingRecovery(
    private val recordingDirectory: File,
    private val recordingStore: RecordingStore,
    private val mediaDurationMillis: (File) -> Long? = ::readMediaDuration,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun recoverInterrupted() {
        recordingStore.loadRecoverable().forEach { stored ->
            val recoveredAt =
                maxOf(
                    currentTimeMillis(),
                    stored.session.startedAtEpochMillis,
                    stored.updatedAtEpochMillis,
                )
            val recovered = repairArchive(stored, recoveredAt)
            if (recovered == null) {
                recordingStore.persist(
                    RecordingState.Failed(
                        sessionId = stored.session.id,
                        code = RecordingErrorCode.CAPTURE_INTERRUPTED,
                    ),
                    updatedAtEpochMillis = recoveredAt,
                )
            } else {
                recordingStore.recoverSaved(recovered, recoveredAt)
            }
        }
    }

    private fun repairArchive(
        stored: StoredRecording,
        recoveredAt: Long,
    ): LocalRecording? =
        when {
            stored.session.fileName.endsWith(WAV_EXTENSION, ignoreCase = true) -> repairWav(stored, recoveredAt)
            stored.session.fileName.endsWith(M4A_EXTENSION, ignoreCase = true) -> repairM4a(stored, recoveredAt)
            else -> null
        }

    private fun repairWav(
        stored: StoredRecording,
        recoveredAt: Long,
    ): LocalRecording? =
        try {
            val archive = archiveFile(stored)
            val retainedFileTimestamp = archive.lastModified()
            val metadata = PcmWavArchiveWriter.repair(archive)
            require(metadata.dataBytes > 0 && metadata.durationMillis > 0) { "recording archive contains no audio" }
            val fileTimestamp = retainedFileTimestamp.takeIf { timestamp -> timestamp > 0 } ?: recoveredAt
            val stoppedAt =
                minOf(
                    recoveredAt,
                    maxOf(stored.session.startedAtEpochMillis, fileTimestamp),
                )
            LocalRecording(
                sessionId = stored.session.id,
                fileName = stored.session.fileName,
                durationMillis = metadata.durationMillis,
                sizeBytes = metadata.sizeBytes,
                sha256 = metadata.sha256,
                stoppedAtEpochMillis = stoppedAt,
            )
        } catch (_: Exception) {
            null
        }

    private fun repairM4a(
        stored: StoredRecording,
        recoveredAt: Long,
    ): LocalRecording? =
        try {
            val archive = archiveFile(stored)
            val sizeBytes = archive.length()
            require(sizeBytes > 0) { "recording archive contains no audio" }
            val durationMillis = mediaDurationMillis(archive)
            require(durationMillis != null && durationMillis > 0) { "recording archive is not readable" }
            val fileTimestamp = archive.lastModified().takeIf { timestamp -> timestamp > 0 } ?: recoveredAt
            val stoppedAt =
                minOf(
                    recoveredAt,
                    maxOf(stored.session.startedAtEpochMillis, fileTimestamp),
                )
            LocalRecording(
                sessionId = stored.session.id,
                fileName = stored.session.fileName,
                durationMillis = durationMillis,
                sizeBytes = sizeBytes,
                sha256 = archive.sha256(),
                stoppedAtEpochMillis = stoppedAt,
            )
        } catch (_: Exception) {
            null
        }

    private fun archiveFile(stored: StoredRecording): File {
        val directory = recordingDirectory.canonicalFile
        val archive = File(directory, stored.session.fileName)
        require(archive.canonicalFile.parentFile == directory) { "recording archive escapes its directory" }
        require(archive.isFile) { "recording archive is unavailable" }
        return archive
    }

    private companion object {
        const val WAV_EXTENSION = ".wav"
        const val M4A_EXTENSION = ".m4a"
    }
}

private fun readMediaDuration(file: File): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { duration -> duration > 0 }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
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
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
