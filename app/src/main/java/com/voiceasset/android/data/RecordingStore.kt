package com.voiceasset.android.data

import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingErrorCode
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import kotlinx.coroutines.flow.Flow

enum class StoredRecordingStatus {
    STARTING,
    RECORDING,
    PAUSING,
    PAUSED,
    RESUMING,
    STOPPING,
    SAVED,
    FAILED,
}

data class StoredRecording(
    val session: RecordingSession,
    val status: StoredRecordingStatus,
    val recording: LocalRecording?,
    val errorCode: RecordingErrorCode?,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(updatedAtEpochMillis >= session.startedAtEpochMillis) {
            "recording update timestamp must not precede its start"
        }
        require((status == StoredRecordingStatus.SAVED) == (recording != null)) {
            "only saved recordings may contain immutable file metadata"
        }
        require((status == StoredRecordingStatus.FAILED) == (errorCode != null)) {
            "only failed recordings may contain an error code"
        }
        require(recording == null || recording.sessionId == session.id) {
            "saved recording must belong to its session"
        }
    }
}

interface RecordingStore {
    fun observeAll(): Flow<List<StoredRecording>>

    suspend fun find(id: RecordingSessionId): StoredRecording?

    suspend fun loadRecoverable(): List<StoredRecording>

    /**
     * Promotes a locally repaired recording after process-death recovery.
     * Normal capture completion must continue through [persist].
     */
    suspend fun recoverSaved(
        recording: LocalRecording,
        updatedAtEpochMillis: Long,
    )

    suspend fun persist(
        state: RecordingState,
        updatedAtEpochMillis: Long,
    )
}
