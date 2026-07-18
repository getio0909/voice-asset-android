package com.voiceasset.android.data.local

import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.data.StoredRecording
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomRecordingStore(
    private val dao: RecordingDao,
) : RecordingStore {
    override fun observeAll(): Flow<List<StoredRecording>> =
        dao.observeAll().map { recordings -> recordings.map(RecordingEntity::toDomain) }

    override suspend fun find(id: RecordingSessionId): StoredRecording? = dao.find(id.value)?.toDomain()

    override suspend fun loadRecoverable(): List<StoredRecording> = dao.loadRecoverable().map(RecordingEntity::toDomain)

    override suspend fun recoverSaved(
        recording: LocalRecording,
        updatedAtEpochMillis: Long,
    ) {
        require(updatedAtEpochMillis >= recording.stoppedAtEpochMillis) {
            "recording recovery timestamp must not precede its stop"
        }
        val changed =
            dao.recoverSaved(
                sessionId = recording.sessionId.value,
                fileName = recording.fileName,
                durationMillis = recording.durationMillis,
                sizeBytes = recording.sizeBytes,
                sha256 = recording.sha256.lowercase(),
                stoppedAtEpochMillis = recording.stoppedAtEpochMillis,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        check(changed == 1) { "cannot recover a terminal, stale, or mismatched recording session" }
    }

    override suspend fun persist(
        state: RecordingState,
        updatedAtEpochMillis: Long,
    ) {
        require(updatedAtEpochMillis >= 0) { "recording update timestamp must not be negative" }
        when (state) {
            RecordingState.Idle -> Unit
            is RecordingState.Starting -> {
                require(updatedAtEpochMillis >= state.session.startedAtEpochMillis) {
                    "recording update timestamp must not precede its start"
                }
                dao.insert(state.session.toEntity(updatedAtEpochMillis))
            }
            is RecordingState.Recording ->
                updateStatus(
                    sessionId = state.session.id,
                    status = StoredRecordingStatus.RECORDING,
                    allowedPreviousStatuses =
                        listOf(
                            StoredRecordingStatus.STARTING,
                            StoredRecordingStatus.RESUMING,
                        ),
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            is RecordingState.Pausing ->
                updateStatus(
                    sessionId = state.session.id,
                    status = StoredRecordingStatus.PAUSING,
                    allowedPreviousStatuses = listOf(StoredRecordingStatus.RECORDING),
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            is RecordingState.Paused ->
                updateStatus(
                    sessionId = state.session.id,
                    status = StoredRecordingStatus.PAUSED,
                    allowedPreviousStatuses = listOf(StoredRecordingStatus.PAUSING),
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            is RecordingState.Resuming ->
                updateStatus(
                    sessionId = state.session.id,
                    status = StoredRecordingStatus.RESUMING,
                    allowedPreviousStatuses = listOf(StoredRecordingStatus.PAUSED),
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            is RecordingState.Stopping ->
                updateStatus(
                    sessionId = state.session.id,
                    status = StoredRecordingStatus.STOPPING,
                    allowedPreviousStatuses =
                        listOf(
                            StoredRecordingStatus.RECORDING,
                            StoredRecordingStatus.PAUSING,
                            StoredRecordingStatus.PAUSED,
                            StoredRecordingStatus.RESUMING,
                        ),
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
            is RecordingState.Saved -> markSaved(state, updatedAtEpochMillis)
            is RecordingState.Failed -> {
                val changed =
                    dao.markFailed(
                        sessionId = state.sessionId.value,
                        errorCode = state.code.name,
                        updatedAtEpochMillis = updatedAtEpochMillis,
                    )
                check(changed == 1) { "cannot fail a recording session that was not persisted" }
            }
        }
    }

    private suspend fun updateStatus(
        sessionId: RecordingSessionId,
        status: StoredRecordingStatus,
        allowedPreviousStatuses: List<StoredRecordingStatus>,
        updatedAtEpochMillis: Long,
    ) {
        val changed =
            dao.updateStatus(
                sessionId = sessionId.value,
                status = status.name,
                allowedPreviousStatuses = allowedPreviousStatuses.map(StoredRecordingStatus::name),
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        check(changed == 1) { "recording persistence rejected an invalid or stale transition" }
    }

    private suspend fun markSaved(
        state: RecordingState.Saved,
        updatedAtEpochMillis: Long,
    ) {
        val recording = state.recording
        require(updatedAtEpochMillis >= recording.stoppedAtEpochMillis) {
            "recording update timestamp must not precede its stop"
        }
        val changed =
            dao.markSaved(
                sessionId = recording.sessionId.value,
                fileName = recording.fileName,
                durationMillis = recording.durationMillis,
                sizeBytes = recording.sizeBytes,
                sha256 = recording.sha256,
                stoppedAtEpochMillis = recording.stoppedAtEpochMillis,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
        check(changed == 1) { "cannot save a recording session that was not persisted" }
    }
}
