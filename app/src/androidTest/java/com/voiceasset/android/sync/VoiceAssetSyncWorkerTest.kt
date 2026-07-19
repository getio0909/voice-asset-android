package com.voiceasset.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.voiceasset.android.TestVoiceAssetApplication
import com.voiceasset.android.VoiceAssetApplication
import com.voiceasset.core.api.AdministrationJob
import com.voiceasset.core.api.AdministrationJobList
import com.voiceasset.core.api.AdministrationSystemStatus
import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.CreateAssetRequest
import com.voiceasset.core.api.CreateUploadRequest
import com.voiceasset.core.api.DeviceSessionList
import com.voiceasset.core.api.LoginResult
import com.voiceasset.core.api.ProviderHealth
import com.voiceasset.core.api.ProviderProfileList
import com.voiceasset.core.api.ProviderProfileState
import com.voiceasset.core.api.RefreshCredential
import com.voiceasset.core.api.ServerCapabilities
import com.voiceasset.core.api.SyncAssetSnapshot
import com.voiceasset.core.api.SyncChange
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.api.TranscriptList
import com.voiceasset.core.api.TranscriptRevision
import com.voiceasset.core.api.TranscriptSegment
import com.voiceasset.core.api.TranscriptSummary
import com.voiceasset.core.api.TranscriptionJob
import com.voiceasset.core.api.TranscriptionJobPayload
import com.voiceasset.core.api.UpdateAssetMetadataRequest
import com.voiceasset.core.api.UploadPart
import com.voiceasset.core.api.UploadSession
import com.voiceasset.core.api.VersionedAsset
import com.voiceasset.core.api.VersionedProviderProfile
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.api.VoiceAssetConnectionException
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.SyncStage
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VoiceAssetSyncWorkerTest {
    @After
    fun resetApiFactory() {
        TestVoiceAssetApplication.apiFactory = null
    }

    @Test
    fun resumesServerRecordedPartsAndQueuesTranscription() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context.applicationContext as VoiceAssetApplication
            val recordingId = RecordingSessionId.parse(UUID.randomUUID().toString())
            val profileId = ServerProfileId.parse(UUID.randomUUID().toString())
            val assetId = UUID.randomUUID().toString()
            val uploadId = UUID.randomUUID().toString()
            val jobId = UUID.randomUUID().toString()
            val profile = profile(profileId)
            application.container.serverProfiles.save(profile)
            application.container.credentials.write(
                profileId,
                "va_worker_token_with_sufficient_entropy".toByteArray(),
            )

            val bytes = ByteArray(PART_SIZE + 3) { index -> (index % 251).toByte() }
            val directory = File(context.filesDir, "recordings").apply { mkdirs() }
            val file = File(directory, "${recordingId.value}.m4a")
            file.writeBytes(bytes)
            seedRecording(application, recordingId, profileId, file.name, bytes)

            val fake =
                FakeVoiceAssetApi(
                    assetId = assetId,
                    uploadId = uploadId,
                    jobId = jobId,
                    fileBytes = bytes,
                    pendingJobReadsBeforeSuccess = 1,
                )
            TestVoiceAssetApplication.apiFactory = { receivedProfile, credential ->
                assertEquals(profile, receivedProfile)
                assertEquals("va_worker_token_with_sufficient_entropy", credential?.value)
                fake
            }
            val worker =
                TestListenableWorkerBuilder<VoiceAssetSyncWorker>(context)
                    .setInputData(workDataOf(VoiceAssetSyncWorker.RECORDING_SESSION_ID to recordingId.value))
                    .build()

            val pendingResult = worker.doWork()

            assertTrue(pendingResult is ListenableWorker.Result.Retry)
            assertEquals(
                SyncStage.TRANSCRIPTION_REQUESTED,
                requireNotNull(application.container.syncTasks.find(recordingId)).stage,
            )
            assertNull(application.container.transcripts.find(recordingId))
            val completedResult =
                TestListenableWorkerBuilder<VoiceAssetSyncWorker>(context)
                    .setInputData(workDataOf(VoiceAssetSyncWorker.RECORDING_SESSION_ID to recordingId.value))
                    .build()
                    .doWork()

            assertTrue(completedResult is ListenableWorker.Result.Success)
            assertEquals(listOf(2), fake.uploadedPartNumbers)
            assertEquals(3, fake.uploadedPartBytes.single().size)
            val task = requireNotNull(application.container.syncTasks.find(recordingId))
            assertEquals(SyncStage.COMPLETE, task.stage)
            assertEquals(bytes.size.toLong(), task.uploadedBytes)
            assertEquals(assetId, task.assetId)
            assertEquals(uploadId, task.uploadId)
            assertEquals(jobId, task.transcriptionJobId)
            assertEquals("Worker transcript", requireNotNull(application.container.transcripts.find(recordingId)).text)

            file.delete()
            application.container.credentials.remove(profileId)
            application.container.serverProfiles.delete(profileId)
        }

    @Test
    fun processRestartRecoversCommittedRemoteOperationsWithoutDuplicates() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context.applicationContext as VoiceAssetApplication
            val recordingId = RecordingSessionId.parse(UUID.randomUUID().toString())
            val profileId = ServerProfileId.parse(UUID.randomUUID().toString())
            val assetId = UUID.randomUUID().toString()
            val uploadId = UUID.randomUUID().toString()
            val jobId = UUID.randomUUID().toString()
            val profile = profile(profileId)
            val bytes = ByteArray(PART_SIZE + 3) { index -> (index % 251).toByte() }
            val directory = File(context.filesDir, "recordings").apply { mkdirs() }
            val file = File(directory, "${recordingId.value}.m4a")
            file.writeBytes(bytes)
            application.container.serverProfiles.save(profile)
            application.container.credentials.write(
                profileId,
                "va_restart_token_with_sufficient_entropy".toByteArray(),
            )
            seedRecording(application, recordingId, profileId, file.name, bytes)
            val fake =
                FakeVoiceAssetApi(
                    assetId = assetId,
                    uploadId = uploadId,
                    jobId = jobId,
                    fileBytes = bytes,
                    recordedPartNumbers = mutableSetOf(),
                    disconnectAfterAssetCommit = true,
                    disconnectAfterUploadCommit = true,
                    disconnectAfterPartCommit = true,
                )
            TestVoiceAssetApplication.apiFactory = { _, _ -> fake }

            try {
                val results =
                    List(4) {
                        TestListenableWorkerBuilder<VoiceAssetSyncWorker>(context)
                            .setInputData(workDataOf(VoiceAssetSyncWorker.RECORDING_SESSION_ID to recordingId.value))
                            .build()
                            .doWork()
                    }

                assertTrue(results.take(3).all { result -> result is ListenableWorker.Result.Retry })
                assertTrue(results.last() is ListenableWorker.Result.Success)
                assertEquals(2, fake.assetIdempotencyKeys.size)
                assertEquals(1, fake.assetIdempotencyKeys.toSet().size)
                assertEquals(2, fake.uploadIdempotencyKeys.size)
                assertEquals(1, fake.uploadIdempotencyKeys.toSet().size)
                assertEquals(listOf(1, 2), fake.uploadedPartNumbers)
                assertEquals(1, fake.uploadedPartNumbers.count { number -> number == 1 })
                val task = requireNotNull(application.container.syncTasks.find(recordingId))
                assertEquals(SyncStage.COMPLETE, task.stage)
                assertEquals(assetId, task.assetId)
                assertEquals(uploadId, task.uploadId)
                assertEquals(jobId, task.transcriptionJobId)
                assertEquals("Worker transcript", requireNotNull(application.container.transcripts.find(recordingId)).text)
            } finally {
                file.delete()
                application.container.credentials.remove(profileId)
                application.container.serverProfiles.delete(profileId)
            }
        }

    @Test
    fun transientNetworkLossBeforePartCommitResumesFromDurableCheckpoint() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context.applicationContext as VoiceAssetApplication
            val recordingId = RecordingSessionId.parse(UUID.randomUUID().toString())
            val profileId = ServerProfileId.parse(UUID.randomUUID().toString())
            val profile = profile(profileId)
            val bytes = ByteArray(PART_SIZE + 3) { index -> (index % 251).toByte() }
            val file =
                File(
                    File(context.filesDir, "recordings").apply { mkdirs() },
                    "${recordingId.value}.m4a",
                )
            file.writeBytes(bytes)
            application.container.serverProfiles.save(profile)
            application.container.credentials.write(
                profileId,
                "va_transient_network_token_with_sufficient_entropy".toByteArray(),
            )
            seedRecording(application, recordingId, profileId, file.name, bytes)
            val fake =
                FakeVoiceAssetApi(
                    assetId = UUID.randomUUID().toString(),
                    uploadId = UUID.randomUUID().toString(),
                    jobId = UUID.randomUUID().toString(),
                    fileBytes = bytes,
                    recordedPartNumbers = mutableSetOf(1),
                    disconnectBeforePartCommit = true,
                )
            TestVoiceAssetApplication.apiFactory = { _, _ -> fake }

            fun worker() =
                TestListenableWorkerBuilder<VoiceAssetSyncWorker>(context)
                    .setInputData(
                        workDataOf(VoiceAssetSyncWorker.RECORDING_SESSION_ID to recordingId.value),
                    ).build()

            try {
                assertTrue(worker().doWork() is ListenableWorker.Result.Retry)
                assertTrue(fake.uploadedPartNumbers.isEmpty())
                assertEquals(
                    SyncStage.UPLOAD_CREATED,
                    requireNotNull(application.container.syncTasks.find(recordingId)).stage,
                )

                assertTrue(worker().doWork() is ListenableWorker.Result.Success)
                assertEquals(listOf(2), fake.uploadedPartNumbers)
                assertEquals(
                    SyncStage.COMPLETE,
                    requireNotNull(application.container.syncTasks.find(recordingId)).stage,
                )
            } finally {
                file.delete()
                application.container.credentials.remove(profileId)
                application.container.serverProfiles.delete(profileId)
            }
        }

    @Test
    fun uploadAndManualTranscriptionRunAsIndependentStages() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context.applicationContext as VoiceAssetApplication
            val recordingId = RecordingSessionId.parse(UUID.randomUUID().toString())
            val profileId = ServerProfileId.parse(UUID.randomUUID().toString())
            val profile = profile(profileId)
            val bytes = "manual transcription recording".encodeToByteArray()
            val file = File(File(context.filesDir, "recordings").apply { mkdirs() }, "${recordingId.value}.m4a")
            file.writeBytes(bytes)
            application.container.serverProfiles.save(profile)
            application.container.credentials.write(
                profileId,
                "va_manual_token_with_sufficient_entropy".toByteArray(),
            )
            seedRecording(
                application,
                recordingId,
                profileId,
                file.name,
                bytes,
                transcriptionPolicyOverride = TranscriptionPolicy.MANUAL,
            )
            val fake =
                FakeVoiceAssetApi(
                    assetId = UUID.randomUUID().toString(),
                    uploadId = UUID.randomUUID().toString(),
                    jobId = UUID.randomUUID().toString(),
                    fileBytes = bytes,
                )
            TestVoiceAssetApplication.apiFactory = { _, _ -> fake }

            try {
                val uploadResult =
                    TestListenableWorkerBuilder<VoiceAssetSyncWorker>(context)
                        .setInputData(
                            workDataOf(
                                VoiceAssetSyncWorker.RECORDING_SESSION_ID to recordingId.value,
                                VoiceAssetSyncWorker.WORK_MODE to VoiceAssetSyncWorker.MODE_UPLOAD_ONLY,
                            ),
                        ).build()
                        .doWork()

                assertTrue(uploadResult is ListenableWorker.Result.Success)
                assertEquals(
                    SyncStage.UPLOAD_COMPLETED,
                    requireNotNull(application.container.syncTasks.find(recordingId)).stage,
                )
                assertTrue(fake.transcriptionRequests.isEmpty())
                assertNull(application.container.transcripts.find(recordingId))
                assertTrue(file.delete())

                val transcriptionResult =
                    TestListenableWorkerBuilder<VoiceAssetSyncWorker>(context)
                        .setInputData(
                            workDataOf(
                                VoiceAssetSyncWorker.RECORDING_SESSION_ID to recordingId.value,
                                VoiceAssetSyncWorker.WORK_MODE to VoiceAssetSyncWorker.MODE_TRANSCRIPTION_ONLY,
                            ),
                        ).build()
                        .doWork()

                assertTrue(transcriptionResult is ListenableWorker.Result.Success)
                assertEquals(SyncStage.COMPLETE, requireNotNull(application.container.syncTasks.find(recordingId)).stage)
                assertEquals(1, fake.transcriptionRequests.size)
                assertEquals("Worker transcript", requireNotNull(application.container.transcripts.find(recordingId)).text)
            } finally {
                file.delete()
                application.container.credentials.remove(profileId)
                application.container.serverProfiles.delete(profileId)
            }
        }

    @Test
    fun incrementalSyncBootstrapsCatalogForCompatibleServerWithoutTheOptionalCapability() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context.applicationContext as VoiceAssetApplication
            val profileId = ServerProfileId.parse(UUID.randomUUID().toString())
            application.container.serverProfiles.save(profile(profileId))
            application.container.credentials.write(
                profileId,
                "va_incremental_skip_token_with_sufficient_entropy".toByteArray(),
            )
            val assetId = UUID.randomUUID().toString()
            val fake =
                FakeVoiceAssetApi.dummy(
                    contractVersion = "0.13.0",
                    catalogPages =
                        mutableListOf(
                            AssetList(
                                items = listOf(remoteAsset(assetId, "Catalog asset")),
                            ),
                        ),
                )
            TestVoiceAssetApplication.apiFactory = { _, _ -> fake }

            try {
                val result = incrementalWorker(context, profileId).doWork()

                assertTrue(result is ListenableWorker.Result.Success)
                assertEquals(0, fake.syncListRequests.size)
                assertEquals(listOf(null to 100), fake.assetListRequests)
                assertNull(application.container.incrementalSync.checkpoint(profileId))
                val cached =
                    application.container.incrementalSync
                        .observeAssets(profileId)
                        .first()
                        .single()
                assertEquals(assetId, cached.assetId)
                assertEquals("Catalog asset", cached.title)
            } finally {
                application.container.credentials.remove(profileId)
                application.container.serverProfiles.delete(profileId)
            }
        }

    @Test
    fun incrementalSyncPersistsAdvertisedServerChanges() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val application = context.applicationContext as VoiceAssetApplication
            val profileId = ServerProfileId.parse(UUID.randomUUID().toString())
            val assetId = UUID.randomUUID().toString()
            application.container.serverProfiles.save(profile(profileId))
            application.container.credentials.write(
                profileId,
                "va_incremental_apply_token_with_sufficient_entropy".toByteArray(),
            )
            val fake =
                FakeVoiceAssetApi.dummy(
                    incrementalSyncSupported = true,
                    syncPages =
                        mutableListOf(
                            SyncChangeList(
                                items = listOf(syncUpsert(assetId)),
                                nextCursor = "cursor-1",
                                hasMore = false,
                            ),
                        ),
                )
            TestVoiceAssetApplication.apiFactory = { _, _ -> fake }

            try {
                val result = incrementalWorker(context, profileId).doWork()

                assertTrue(result is ListenableWorker.Result.Success)
                assertEquals(listOf(null to 100), fake.syncListRequests)
                assertEquals(
                    "cursor-1",
                    application.container.incrementalSync
                        .checkpoint(profileId)
                        ?.cursor,
                )
                val cached =
                    application.container.incrementalSync
                        .observeAssets(profileId)
                        .first()
                        .single()
                assertEquals(assetId, cached.assetId)
                assertEquals("Synced asset", cached.title)
            } finally {
                application.container.credentials.remove(profileId)
                application.container.serverProfiles.delete(profileId)
            }
        }

    private fun incrementalWorker(
        context: Context,
        profileId: ServerProfileId,
    ): IncrementalSyncWorker =
        TestListenableWorkerBuilder<IncrementalSyncWorker>(context)
            .setInputData(workDataOf(IncrementalSyncWorker.SERVER_PROFILE_ID to profileId.value))
            .build()

    private fun syncUpsert(assetId: String): SyncChange =
        SyncChange(
            sequence = 1,
            entityType = "asset",
            entityId = assetId,
            operation = "upsert",
            entityVersion = 1,
            changedAt = NOW,
            asset =
                SyncAssetSnapshot(
                    id = assetId,
                    collectionId = null,
                    title = "Synced asset",
                    language = "en-US",
                    status = "ready",
                    durationMillis = 1_000,
                    version = 1,
                    createdAt = NOW,
                    updatedAt = NOW,
                    trashedAt = null,
                ),
        )

    private fun remoteAsset(
        assetId: String,
        title: String,
    ): Asset =
        Asset(
            id = assetId,
            workspaceId = "20000000-0000-4000-8000-000000000002",
            collectionId = null,
            title = title,
            language = "en-US",
            status = "ready",
            durationMillis = 1_000,
            version = 1,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private suspend fun seedRecording(
        application: VoiceAssetApplication,
        recordingId: RecordingSessionId,
        profileId: ServerProfileId,
        fileName: String,
        bytes: ByteArray,
        uploadPolicyOverride: UploadPolicy? = null,
        transcriptionPolicyOverride: TranscriptionPolicy? = null,
    ) {
        val session =
            RecordingSession(
                id = recordingId,
                fileName = fileName,
                startedAtEpochMillis = 1,
                serverProfileId = profileId,
                uploadPolicyOverride = uploadPolicyOverride,
                transcriptionPolicyOverride = transcriptionPolicyOverride,
            )
        val recordings = application.container.recordings
        recordings.persist(RecordingState.Starting(session), 1)
        recordings.persist(RecordingState.Recording(session), 2)
        recordings.persist(RecordingState.Stopping(session), 3)
        recordings.persist(
            RecordingState.Saved(
                LocalRecording(
                    sessionId = recordingId,
                    fileName = fileName,
                    durationMillis = 1_000,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = bytes.sha256(),
                    stoppedAtEpochMillis = 4,
                ),
            ),
            5,
        )
    }

    private fun profile(
        profileId: ServerProfileId,
        transcriptionPolicy: TranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
    ): ServerProfile =
        ServerProfile.create(
            id = profileId,
            name = "Worker test",
            baseUrl = "https://example.test",
            authenticationMode = AuthenticationMode.LOCAL_SESSION,
            defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
            defaultTranscriptionPolicy = transcriptionPolicy,
            customCaPem = null,
            certificateFingerprint = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
}

private class FakeVoiceAssetApi(
    private val assetId: String,
    private val uploadId: String,
    private val jobId: String,
    private val fileBytes: ByteArray,
    private val recordedPartNumbers: MutableSet<Int> = mutableSetOf(1),
    private val disconnectAfterAssetCommit: Boolean = false,
    private val disconnectAfterUploadCommit: Boolean = false,
    private val disconnectAfterPartCommit: Boolean = false,
    private val disconnectBeforePartCommit: Boolean = false,
    private val incrementalSyncSupported: Boolean = false,
    private val contractVersion: String = "0.22.0",
    private val catalogPages: MutableList<AssetList> = mutableListOf(AssetList(emptyList())),
    private val syncPages: MutableList<SyncChangeList> =
        mutableListOf(SyncChangeList(emptyList(), "test-cursor", false)),
    pendingJobReadsBeforeSuccess: Int = 0,
) : VoiceAssetApi {
    val uploadedPartNumbers = mutableListOf<Int>()
    val uploadedPartBytes = mutableListOf<ByteArray>()
    val assetIdempotencyKeys = mutableListOf<String>()
    val uploadIdempotencyKeys = mutableListOf<String>()
    val transcriptionRequests = mutableListOf<Pair<String, String>>()
    val syncListRequests = mutableListOf<Pair<String?, Int>>()
    val assetListRequests = mutableListOf<Pair<String?, Int>>()
    private lateinit var uploadDeclaration: CreateUploadRequest
    private var assetDisconnectDelivered = false
    private var uploadDisconnectDelivered = false
    private var partDisconnectDelivered = false
    private val transcriptId = UUID.randomUUID().toString()
    private val revisionId = UUID.randomUUID().toString()
    private val segmentId = UUID.randomUUID().toString()
    private var pendingJobReadsRemaining = pendingJobReadsBeforeSuccess

    override fun getCapabilities(): ServerCapabilities =
        ServerCapabilities(
            serverVersion = "0.1.0-dev",
            apiVersion = "v1",
            contractVersion = contractVersion,
            features =
                buildList {
                    addAll(
                        listOf(
                            "capability_negotiation",
                            "m4a_uploads",
                            "refresh_sessions",
                            "resumable_uploads",
                            "structured_errors",
                            "transcription_jobs",
                        ),
                    )
                    if (incrementalSyncSupported) {
                        add("incremental_sync")
                    }
                },
        )

    override fun listAdministrationJobs(
        cursor: String?,
        limit: Int,
    ): AdministrationJobList = error("not used")

    override fun retryAdministrationJob(jobId: String): AdministrationJob = error("not used")

    override fun getAdministrationSystemStatus(): AdministrationSystemStatus = error("not used")

    override fun listAsrProviderProfiles(): ProviderProfileList = error("not used")

    override fun updateAsrProviderProfileState(
        profileId: String,
        expectedVersion: Long,
        state: ProviderProfileState,
    ): VersionedProviderProfile = error("not used")

    override fun checkAsrProviderProfileHealth(profileId: String): ProviderHealth = error("not used")

    override fun listLlmProviderProfiles(): ProviderProfileList = error("not used")

    override fun updateLlmProviderProfileState(
        profileId: String,
        expectedVersion: Long,
        state: ProviderProfileState,
    ): VersionedProviderProfile = error("not used")

    override fun checkLlmProviderProfileHealth(profileId: String): ProviderHealth = error("not used")

    override fun login(
        email: String,
        password: String,
    ): LoginResult = error("not used")

    override fun refreshSession(refreshCredential: RefreshCredential): LoginResult = error("not used")

    override fun listDeviceSessions(): DeviceSessionList = error("not used")

    override fun revokeDeviceSession(deviceSessionId: String) = error("not used")

    override fun changePassword(
        currentPassword: String,
        newPassword: String,
    ) = error("not used")

    override fun createAsset(
        input: CreateAssetRequest,
        idempotencyKey: String,
    ): Asset {
        assetIdempotencyKeys += idempotencyKey
        if (disconnectAfterAssetCommit && !assetDisconnectDelivered) {
            assetDisconnectDelivered = true
            disconnectAfterCommit("asset")
        }
        return Asset(
            id = assetId,
            workspaceId = UUID.randomUUID().toString(),
            collectionId = null,
            title = input.title,
            language = input.language,
            status = "draft",
            durationMillis = null,
            version = 1,
            createdAt = NOW,
            updatedAt = NOW,
        )
    }

    override fun getAsset(assetId: String): VersionedAsset = error("not used")

    override fun updateAssetMetadata(
        assetId: String,
        expectedEntityTag: String,
        input: UpdateAssetMetadataRequest,
    ): VersionedAsset = error("not used")

    override fun listAssets(
        cursor: String?,
        limit: Int,
    ): AssetList {
        assetListRequests += cursor to limit
        return if (catalogPages.size > 1) catalogPages.removeAt(0) else catalogPages.single()
    }

    override fun listSyncChanges(
        cursor: String?,
        limit: Int,
    ): SyncChangeList {
        syncListRequests += cursor to limit
        return if (syncPages.size > 1) syncPages.removeAt(0) else syncPages.single()
    }

    override fun createUpload(
        input: CreateUploadRequest,
        idempotencyKey: String,
    ): UploadSession {
        uploadIdempotencyKeys += idempotencyKey
        uploadDeclaration = input
        if (disconnectAfterUploadCommit && !uploadDisconnectDelivered) {
            uploadDisconnectDelivered = true
            disconnectAfterCommit("upload")
        }
        return upload(input, "active", null)
    }

    override fun getUpload(uploadId: String): UploadSession =
        upload(
            input = uploadDeclaration,
            state = "active",
            parts =
                recordedPartNumbers.sorted().map { number ->
                    val offset = (number - 1) * PART_SIZE
                    val bytes = fileBytes.copyOfRange(offset, minOf(offset + PART_SIZE, fileBytes.size))
                    UploadPart(
                        number = number,
                        sizeBytes = bytes.size.toLong(),
                        sha256 = bytes.sha256(),
                        createdAt = NOW,
                    )
                },
        )

    override fun putUploadPart(
        uploadId: String,
        partNumber: Int,
        bytes: ByteArray,
        partSha256: String,
    ): UploadPart {
        if (disconnectBeforePartCommit && !partDisconnectDelivered) {
            partDisconnectDelivered = true
            disconnectBeforeCommit("part")
        }
        uploadedPartNumbers += partNumber
        uploadedPartBytes += bytes.copyOf()
        assertEquals(bytes.sha256(), partSha256)
        val offset = (partNumber - 1) * PART_SIZE
        assertTrue(bytes.contentEquals(fileBytes.copyOfRange(offset, minOf(offset + PART_SIZE, fileBytes.size))))
        recordedPartNumbers += partNumber
        if (disconnectAfterPartCommit && !partDisconnectDelivered) {
            partDisconnectDelivered = true
            disconnectAfterCommit("part")
        }
        return UploadPart(partNumber, bytes.size.toLong(), partSha256, NOW)
    }

    override fun completeUpload(uploadId: String): UploadSession = upload(uploadDeclaration, "completed", null)

    override fun createTranscription(
        assetId: String,
        idempotencyKey: String,
    ): TranscriptionJob {
        transcriptionRequests += assetId to idempotencyKey
        return transcriptionJob(assetId, "queued", resultRevisionId = null)
    }

    override fun getTranscriptionJob(jobId: String): TranscriptionJob {
        assertEquals(this.jobId, jobId)
        if (pendingJobReadsRemaining > 0) {
            pendingJobReadsRemaining -= 1
            return transcriptionJob(assetId, "queued", resultRevisionId = null)
        }
        return transcriptionJob(assetId, "succeeded", resultRevisionId = revisionId)
    }

    override fun listAssetTranscripts(assetId: String): TranscriptList =
        TranscriptList(
            items =
                listOf(
                    TranscriptSummary(
                        id = transcriptId,
                        assetId = assetId,
                        language = "en-US",
                        latestRevisionId = revisionId,
                        latestKind = "normalized",
                        latestText = "Worker transcript",
                        createdAt = NOW,
                        revisionCreatedAt = NOW,
                    ),
                ),
        )

    override fun getTranscriptRevision(revisionId: String): TranscriptRevision {
        assertEquals(this.revisionId, revisionId)
        return TranscriptRevision(
            id = revisionId,
            transcriptId = transcriptId,
            assetId = assetId,
            kind = "normalized",
            language = "en-US",
            text = "Worker transcript",
            providerSnapshot = JsonObject(mapOf("provider_id" to JsonPrimitive("mock_asr"))),
            hotwordSnapshot = JsonObject(emptyMap()),
            glossarySnapshot = JsonObject(emptyMap()),
            diff = JsonObject(emptyMap()),
            validationResult = JsonObject(emptyMap()),
            sourceJobId = jobId,
            createdByType = "system",
            reviewStatus = "pending",
            createdAt = NOW,
            segments =
                listOf(
                    TranscriptSegment(
                        id = segmentId,
                        ordinal = 0,
                        startMillis = 0,
                        endMillis = 1_000,
                        speaker = null,
                        text = "Worker transcript",
                        confidence = 1.0,
                        words = emptyList(),
                    ),
                ),
        )
    }

    private fun transcriptionJob(
        assetId: String,
        state: String,
        resultRevisionId: String?,
    ): TranscriptionJob =
        TranscriptionJob(
            id = jobId,
            workspaceId = UUID.randomUUID().toString(),
            assetId = assetId,
            createdBy = UUID.randomUUID().toString(),
            kind = "mock_transcribe",
            state = state,
            payload = TranscriptionJobPayload(assetId),
            attempts = 0,
            maxAttempts = 3,
            availableAt = NOW,
            resultRevisionId = resultRevisionId,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun upload(
        input: CreateUploadRequest,
        state: String,
        parts: List<UploadPart>?,
    ): UploadSession =
        UploadSession(
            id = uploadId,
            assetId = input.assetId,
            workspaceId = UUID.randomUUID().toString(),
            filename = input.filename,
            mimeType = input.mimeType,
            expectedSize = input.sizeBytes,
            expectedSha256 = input.sha256,
            partSize = PART_SIZE,
            state = state,
            expiresAt = NOW,
            createdAt = NOW,
            updatedAt = NOW,
            completedAt = if (state == "completed") NOW else null,
            errorCode = null,
            parts = parts,
        )

    private fun disconnectAfterCommit(operation: String): Nothing =
        throw VoiceAssetConnectionException(
            "connection lost after $operation commit",
            IOException("simulated connection loss"),
        )

    private fun disconnectBeforeCommit(operation: String): Nothing =
        throw VoiceAssetConnectionException(
            "connection lost before $operation commit",
            IOException("simulated connection loss"),
        )

    companion object {
        fun dummy(
            incrementalSyncSupported: Boolean = false,
            contractVersion: String = "0.22.0",
            catalogPages: MutableList<AssetList> = mutableListOf(AssetList(emptyList())),
            syncPages: MutableList<SyncChangeList> =
                mutableListOf(SyncChangeList(emptyList(), "test-cursor", false)),
        ): FakeVoiceAssetApi =
            FakeVoiceAssetApi(
                assetId = UUID.randomUUID().toString(),
                uploadId = UUID.randomUUID().toString(),
                jobId = UUID.randomUUID().toString(),
                fileBytes = byteArrayOf(),
                incrementalSyncSupported = incrementalSyncSupported,
                contractVersion = contractVersion,
                catalogPages = catalogPages,
                syncPages = syncPages,
            )
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private const val PART_SIZE = 5_242_880
private const val NOW = "2026-07-16T08:00:00Z"
