package com.voiceasset.android.recording

import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.data.StoredRecording
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingErrorCode
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class RecordingRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `repairs retained PCM WAV and promotes it to saved`() =
        runBlocking {
            val directory = temporaryFolder.newFolder("recordings")
            val stored = recoverableRecording("e2ea48f7-fdfd-4135-8ab0-f33895858852", ".wav")
            val archive = directory.resolve(stored.session.fileName)
            PcmWavArchiveWriter(archive).apply {
                writeFrame(ByteArray(640) { 1 })
                writeFrame(ByteArray(640) { 2 })
                finish()
            }
            RandomAccessFile(archive, "rw").use { file ->
                file.seek(4)
                file.write(byteArrayOf(0, 0, 0, 0))
                file.seek(40)
                file.write(byteArrayOf(0, 0, 0, 0))
            }
            check(archive.setLastModified(1_500_000_000_000L))
            val store = FakeRecordingStore(stored)

            RecordingRecovery(directory, store) { 1_600_000_000_000L }.recoverInterrupted()

            val recovered = requireNotNull(store.recovered)
            assertEquals(stored.session.id, recovered.sessionId)
            assertEquals(stored.session.fileName, recovered.fileName)
            assertEquals(40L, recovered.durationMillis)
            assertEquals(1_324L, recovered.sizeBytes)
            assertEquals(1_500_000_000_000L, recovered.stoppedAtEpochMillis)
            assertEquals(1_600_000_000_000L, requireNotNull(store.recoveredAt))
            assertNull(store.failed)
        }

    @Test
    fun `marks missing WAV and unsupported archive as interrupted`() =
        runBlocking {
            val directory = temporaryFolder.newFolder("recordings")
            val missing = FakeRecordingStore(recoverableRecording("8b6c0125-cc1d-4516-a4d0-f51c21e29151", ".wav"))
            RecordingRecovery(directory, missing) { 2_000 }.recoverInterrupted()
            assertEquals(RecordingErrorCode.CAPTURE_INTERRUPTED, missing.failed)
            assertNull(missing.recovered)

            val unsupported = FakeRecordingStore(recoverableRecording("52d59975-03e0-49b0-81aa-72ccece130ee", ".m4a"))
            directory.resolve(unsupported.stored.session.fileName).writeBytes(ByteArray(128))
            RecordingRecovery(
                directory,
                unsupported,
                currentTimeMillis = { 2_100 },
                mediaDurationMillis = { null },
            ).recoverInterrupted()
            assertEquals(RecordingErrorCode.CAPTURE_INTERRUPTED, unsupported.failed)
            assertNull(unsupported.recovered)
        }

    @Test
    fun `promotes readable M4A after process death`() =
        runBlocking {
            val directory = temporaryFolder.newFolder("recordings")
            val stored = recoverableRecording("6e3bdbf0-7ba9-470d-a2c7-bb7b817c7c55", ".m4a")
            val archive = directory.resolve(stored.session.fileName)
            archive.writeBytes(ByteArray(128) { value -> value.toByte() })
            check(archive.setLastModified(1_500))
            val store = FakeRecordingStore(stored)

            RecordingRecovery(
                directory,
                store,
                currentTimeMillis = { 2_000 },
                mediaDurationMillis = { 1_250 },
            ).recoverInterrupted()

            val recovered = requireNotNull(store.recovered)
            assertEquals(stored.session.id, recovered.sessionId)
            assertEquals(stored.session.fileName, recovered.fileName)
            assertEquals(1_250L, recovered.durationMillis)
            assertEquals(128L, recovered.sizeBytes)
            assertEquals(1_500L, recovered.stoppedAtEpochMillis)
            assertEquals(64, recovered.sha256.length)
            assertNull(store.failed)
        }

    @Test
    fun `rejects header-only WAV instead of publishing an empty recording`() =
        runBlocking {
            val directory = temporaryFolder.newFolder("recordings")
            val stored = recoverableRecording("c81ff94f-a7a8-4444-95f6-d0ce47dbe69d", ".wav")
            PcmWavArchiveWriter(directory.resolve(stored.session.fileName)).finish()
            val store = FakeRecordingStore(stored)

            RecordingRecovery(directory, store) { 2_000 }.recoverInterrupted()

            assertEquals(RecordingErrorCode.CAPTURE_INTERRUPTED, store.failed)
            assertNull(store.recovered)
        }

    private fun recoverableRecording(
        id: String,
        suffix: String,
    ): StoredRecording =
        StoredRecording(
            session =
                RecordingSession(
                    id = RecordingSessionId.parse(id),
                    fileName = "$id$suffix",
                    startedAtEpochMillis = 1_000,
                ),
            status = StoredRecordingStatus.RECORDING,
            recording = null,
            errorCode = null,
            updatedAtEpochMillis = 1_200,
        )

    private class FakeRecordingStore(
        val stored: StoredRecording,
    ) : RecordingStore {
        var recovered: LocalRecording? = null
        var recoveredAt: Long? = null
        var failed: RecordingErrorCode? = null

        override fun observeAll(): Flow<List<StoredRecording>> = flowOf(listOf(stored))

        override suspend fun find(id: RecordingSessionId): StoredRecording? = stored.takeIf { it.session.id == id }

        override suspend fun loadRecoverable(): List<StoredRecording> = listOf(stored)

        override suspend fun recoverSaved(
            recording: LocalRecording,
            updatedAtEpochMillis: Long,
        ) {
            recovered = recording
            recoveredAt = updatedAtEpochMillis
        }

        override suspend fun persist(
            state: RecordingState,
            updatedAtEpochMillis: Long,
        ) {
            require(state is RecordingState.Failed)
            failed = state.code
        }
    }
}
