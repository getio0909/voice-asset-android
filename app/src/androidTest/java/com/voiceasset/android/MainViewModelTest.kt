package com.voiceasset.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.android.administration.MobileAdministration
import com.voiceasset.android.administration.MobileAdministrationProviderProfile
import com.voiceasset.android.administration.MobileAdministrationSnapshot
import com.voiceasset.android.administration.ProviderProfileFamily
import com.voiceasset.android.asset.AssetMetadataEditSession
import com.voiceasset.android.asset.AssetMetadataEditor
import com.voiceasset.android.data.CachedRemoteAsset
import com.voiceasset.android.data.IncrementalSyncCheckpoint
import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.android.data.StoredRecording
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.android.data.SyncTaskStore
import com.voiceasset.android.data.TranscriptStore
import com.voiceasset.android.data.preferences.ActiveProfileStore
import com.voiceasset.android.security.PersonalDeviceSessions
import com.voiceasset.android.security.PersonalDeviceSessionsSnapshot
import com.voiceasset.android.security.ServerCredentialStore
import com.voiceasset.android.security.StoredServerSession
import com.voiceasset.android.sync.RecordingSyncEnqueuer
import com.voiceasset.android.sync.RemoteAssetSyncScheduler
import com.voiceasset.core.api.AdministrationAssetStatus
import com.voiceasset.core.api.AdministrationJob
import com.voiceasset.core.api.AdministrationJobStatus
import com.voiceasset.core.api.AdministrationProviderStatus
import com.voiceasset.core.api.AdministrationStorageStatus
import com.voiceasset.core.api.AdministrationSystemStatus
import com.voiceasset.core.api.AdministrationTranscriptStatus
import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.DevicePairingAuthenticator
import com.voiceasset.core.api.DevicePairingResult
import com.voiceasset.core.api.DeviceSession
import com.voiceasset.core.api.LoginResult
import com.voiceasset.core.api.Principal
import com.voiceasset.core.api.ProviderHealth
import com.voiceasset.core.api.ProviderHealthStatus
import com.voiceasset.core.api.ProviderProfile
import com.voiceasset.core.api.ProviderProfileState
import com.voiceasset.core.api.RefreshCredential
import com.voiceasset.core.api.ServerProfileAuthenticator
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.api.VoiceAssetApiException
import com.voiceasset.core.api.WebSession
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.LocalTranscript
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.SyncBlockReason
import com.voiceasset.core.model.SyncEvent
import com.voiceasset.core.model.SyncStage
import com.voiceasset.core.model.SyncStateMachine
import com.voiceasset.core.model.SyncTask
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class MainViewModelTest {
    @Test
    fun initialStateDoesNotAuthenticateOrScheduleRemoteSync() =
        runBlocking {
            val authenticator = FakeAuthenticator()
            val remoteScheduler = FakeRemoteAssetSyncScheduler()
            val viewModel =
                MainViewModel(
                    profiles = FakeServerProfileRepository(),
                    activeProfile = FakeActiveProfileStore(),
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = FakeCredentialStore(),
                    authenticator = authenticator,
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                    remoteAssetSyncScheduler = remoteScheduler,
                )

            val state =
                withTimeout(5_000) {
                    viewModel.uiState.first { current ->
                        current.initializationStatus == InitializationStatus.INITIALIZED
                    }
                }

            assertEquals(ServerStatus.NOT_CONFIGURED, state.serverStatus)
            assertEquals(RecordingUiStatus.READY, state.recordingStatus)
            assertNull(authenticator.email)
            assertEquals(emptyList<ServerProfileId>(), remoteScheduler.scheduled)
        }

    @Test
    fun selectingOrRefreshingProfileWithoutSessionDoesNotScheduleRemoteSync() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val credentials = FakeCredentialStore()
            val remoteScheduler = FakeRemoteAssetSyncScheduler()
            val firstProfileId = ServerProfileId.parse("71000000-0000-4000-8000-000000000001")
            val secondProfileId = ServerProfileId.parse("71000000-0000-4000-8000-000000000002")
            repository.save(mainViewModelServerProfile(firstProfileId))
            repository.save(mainViewModelServerProfile(secondProfileId))
            activeProfile.set(firstProfileId)
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = credentials,
                    authenticator = FakeAuthenticator(),
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                    remoteAssetSyncScheduler = remoteScheduler,
                )

            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.activeServerProfileId == firstProfileId.value }
            }
            viewModel.selectServerProfile(secondProfileId.value)
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.activeServerProfileId == secondProfileId.value }
            }
            delay(100)
            viewModel.refreshRemoteAssets()
            delay(100)

            assertEquals(emptyList<ServerProfileId>(), remoteScheduler.refreshed)
        }

    @Test
    fun pairingPayloadCreatesActiveProfileAndClearsOneTimeSecret() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val credentialStore = FakeCredentialStore()
            val remoteScheduler = FakeRemoteAssetSyncScheduler()
            val profileId = ServerProfileId.parse("72636d78-31f6-4349-899a-a87bb8bb6814")
            val pairingAuthenticator = FakePairingAuthenticator()
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = credentialStore,
                    authenticator = FakeAuthenticator(),
                    pairingAuthenticator = pairingAuthenticator,
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                    remoteAssetSyncScheduler = remoteScheduler,
                    clock = { 1_000 },
                    idFactory = { profileId },
                )

            viewModel.updatePairingPayload(PAIRING_PAYLOAD)
            viewModel.updateUploadPolicy(UploadPolicy.ANY_NETWORK)
            viewModel.updateTranscriptionPolicy(TranscriptionPolicy.MANUAL)
            viewModel.pairServerProfile()

            val state =
                withTimeout(5_000) {
                    viewModel.uiState.first { it.serverStatus == ServerStatus.CONFIGURED }
                }
            assertEquals(PAIRING_PAYLOAD, pairingAuthenticator.payload)
            assertEquals(SecretInput.EMPTY, state.serverDraft.pairingPayload)
            assertEquals("api.getio.net", state.serverProfiles.single().name)
            assertEquals("https://api.getio.net:10443", state.serverProfiles.single().origin)
            assertEquals(UploadPolicy.ANY_NETWORK, state.serverProfiles.single().uploadPolicy)
            assertEquals(TranscriptionPolicy.MANUAL, state.serverProfiles.single().transcriptionPolicy)
            assertEquals(profileId, activeProfile.value.value)
            val storedSession = requireNotNull(credentialStore.readSession(profileId))
            assertEquals(TEST_CREDENTIAL, storedSession.accessCredential.value)
            assertEquals("va_rft_${"r".repeat(43)}", storedSession.refreshCredential?.value)
            assertEquals(listOf(profileId), remoteScheduler.scheduled)
        }

    @Test
    fun pairingProfileSaveFailureRemovesProtectedCredentialAndKeepsPayloadCleared() =
        runBlocking {
            val repository = FakeServerProfileRepository(saveFailure = IllegalStateException("disk unavailable"))
            val activeProfile = FakeActiveProfileStore()
            val credentialStore = FakeCredentialStore()
            val profileId = ServerProfileId.parse("73636d78-31f6-4349-899a-a87bb8bb6814")
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = credentialStore,
                    authenticator = FakeAuthenticator(),
                    pairingAuthenticator = FakePairingAuthenticator(),
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                    clock = { 1_000 },
                    idFactory = { profileId },
                )

            viewModel.updatePairingPayload(PAIRING_PAYLOAD)
            viewModel.pairServerProfile()

            val state =
                withTimeout(5_000) {
                    viewModel.uiState.first { it.serverFormError == ServerProfileFormError.SAVE_FAILED }
                }
            assertEquals(SecretInput.EMPTY, state.serverDraft.pairingPayload)
            assertNull(credentialStore.read(profileId))
            assertNull(activeProfile.value.value)
            assertEquals(emptyList<ServerProfile>(), repository.value.value)
        }

    @Test
    fun validDraftPersistsAndActivatesNormalizedProfile() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val credentialStore = FakeCredentialStore()
            val authenticator = FakeAuthenticator()
            val profileId = ServerProfileId.parse("82636d78-31f6-4349-899a-a87bb8bb6814")
            val secondProfileId = ServerProfileId.parse("93636d78-31f6-4349-899a-a87bb8bb6814")
            val secondProfile =
                ServerProfile.create(
                    id = secondProfileId,
                    name = "Archive server",
                    baseUrl = "https://archive.example.test",
                    authenticationMode = AuthenticationMode.LOCAL_SESSION,
                    defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
                    defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
                    customCaPem = null,
                    certificateFingerprint = null,
                    createdAtEpochMillis = 500,
                    updatedAtEpochMillis = 500,
                )
            val recordingId = RecordingSessionId.parse("50000000-0000-4000-8000-000000000005")
            val secondRecordingId = RecordingSessionId.parse("51000000-0000-4000-8000-000000000005")
            val localRecording =
                LocalRecording(
                    sessionId = recordingId,
                    fileName = "field-note.m4a",
                    durationMillis = 65_000,
                    sizeBytes = 1_024,
                    sha256 = "ab".repeat(32),
                    stoppedAtEpochMillis = 900,
                )
            val secondLocalRecording =
                LocalRecording(
                    sessionId = secondRecordingId,
                    fileName = "archive-note.m4a",
                    durationMillis = 90_000,
                    sizeBytes = 2_048,
                    sha256 = "cd".repeat(32),
                    stoppedAtEpochMillis = 950,
                )
            val recordingStore =
                FakeRecordingStore(
                    listOf(
                        StoredRecording(
                            session =
                                RecordingSession(
                                    id = recordingId,
                                    fileName = localRecording.fileName,
                                    startedAtEpochMillis = 500,
                                    serverProfileId = profileId,
                                ),
                            status = StoredRecordingStatus.SAVED,
                            recording = localRecording,
                            errorCode = null,
                            updatedAtEpochMillis = 900,
                        ),
                        StoredRecording(
                            session =
                                RecordingSession(
                                    id = secondRecordingId,
                                    fileName = secondLocalRecording.fileName,
                                    startedAtEpochMillis = 500,
                                    serverProfileId = secondProfileId,
                                ),
                            status = StoredRecordingStatus.SAVED,
                            recording = secondLocalRecording,
                            errorCode = null,
                            updatedAtEpochMillis = 950,
                        ),
                    ),
                )
            val syncTaskStore =
                FakeSyncTaskStore(
                    listOf(
                        SyncTask.restore(
                            recordingSessionId = recordingId,
                            serverProfileId = profileId,
                            stage = SyncStage.COMPLETE,
                            assetId = "60000000-0000-4000-8000-000000000006",
                            uploadId = "61000000-0000-4000-8000-000000000006",
                            transcriptionJobId = null,
                            uploadedBytes = 1_024,
                            totalBytes = 1_024,
                            attemptCount = 0,
                            lastErrorCode = null,
                            blockReason = SyncBlockReason.NONE,
                            createdAtEpochMillis = 900,
                            updatedAtEpochMillis = 1_000,
                        ),
                        SyncStateMachine.transition(
                            task =
                                SyncStateMachine.transition(
                                    task =
                                        SyncTask.create(
                                            recordingSessionId = secondRecordingId,
                                            serverProfileId = secondProfileId,
                                            totalBytes = 2_048,
                                            createdAtEpochMillis = 950,
                                        ),
                                    event = SyncEvent.AssetCreated("71000000-0000-4000-8000-000000000007"),
                                    updatedAtEpochMillis = 960,
                                ),
                            event = SyncEvent.PermanentFailure("retry_exhausted"),
                            updatedAtEpochMillis = 970,
                        ),
                    ),
                )
            val syncScheduler = FakeRecordingSyncEnqueuer()
            val remoteAssetSyncScheduler = FakeRemoteAssetSyncScheduler()
            val incrementalSync =
                FakeIncrementalSyncStore(
                    mapOf(
                        profileId to
                            listOf(
                                CachedRemoteAsset(
                                    serverProfileId = profileId,
                                    assetId = "60000000-0000-4000-8000-000000000006",
                                    collectionId = null,
                                    title = "Synced interview",
                                    language = "en-US",
                                    status = "ready",
                                    durationMillis = 65_000,
                                    version = 3,
                                    changeSequence = 7,
                                    createdAtEpochMillis = 500,
                                    updatedAtEpochMillis = 900,
                                    trashedAtEpochMillis = null,
                                ),
                            ),
                        secondProfileId to
                            listOf(
                                CachedRemoteAsset(
                                    serverProfileId = secondProfileId,
                                    assetId = "70000000-0000-4000-8000-000000000007",
                                    collectionId = null,
                                    title = "Archived meeting",
                                    language = "en-US",
                                    status = "ready",
                                    durationMillis = 90_000,
                                    version = 1,
                                    changeSequence = 2,
                                    createdAtEpochMillis = 400,
                                    updatedAtEpochMillis = 800,
                                    trashedAtEpochMillis = null,
                                ),
                            ),
                    ),
                )
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = recordingStore,
                    syncTasks = syncTaskStore,
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = incrementalSync,
                    syncScheduler = syncScheduler,
                    credentials = credentialStore,
                    authenticator = authenticator,
                    assetMetadataEditor = FakeAssetMetadataEditor(incrementalSync),
                    remoteAssetSyncScheduler = remoteAssetSyncScheduler,
                    clock = { 1_000 },
                    idFactory = { profileId },
                )

            viewModel.updateServerName(" Test server ")
            viewModel.updateServerUrl("https://API.GETIO.NET:10443/")
            viewModel.updateServerEmail("owner@example.test")
            viewModel.updateServerPassword("test-password")
            viewModel.updateCertificateFingerprint("ab".repeat(32))
            viewModel.updateUploadPolicy(UploadPolicy.ANY_NETWORK)
            viewModel.updateTranscriptionPolicy(TranscriptionPolicy.MANUAL)
            viewModel.saveServerProfile()

            val state =
                withTimeout(15_000) {
                    viewModel.uiState.first {
                        it.serverStatus == ServerStatus.CONFIGURED && it.syncedAssetCount == 1
                    }
                }
            assertEquals("Test server", state.serverProfiles.single().name)
            assertEquals("https://api.getio.net:10443", state.serverProfiles.single().origin)
            assertEquals(UploadPolicy.ANY_NETWORK, state.serverProfiles.single().uploadPolicy)
            assertEquals(TranscriptionPolicy.MANUAL, state.serverProfiles.single().transcriptionPolicy)
            assertEquals(profileId, activeProfile.value.value)
            assertEquals("owner@example.test", authenticator.email)
            val storedSession = requireNotNull(credentialStore.readSession(profileId))
            assertEquals(TEST_CREDENTIAL, storedSession.accessCredential.value)
            assertEquals("va_rft_${"r".repeat(43)}", storedSession.refreshCredential?.value)
            assertEquals(SecretInput.EMPTY, state.serverDraft.password)
            assertEquals(1, state.syncedAssetCount)
            assertEquals(1, state.syncedAssetMatchCount)
            assertEquals("Synced interview", state.syncedAssets.single().title)
            assertEquals(1, state.localRecordingCount)
            assertEquals(1, state.localRecordingMatchCount)
            assertEquals("field-note.m4a", state.localRecordings.single().fileName)
            assertEquals(SyncUiStatus.COMPLETE, state.localRecordings.single().syncStatus)
            assertEquals(true, state.localRecordings.single().canPlay)
            assertEquals(true, state.localRecordings.single().canExport)
            assertEquals(TranscriptionPolicy.MANUAL, state.activeRecordingTranscriptionPolicy)
            assertEquals(listOf(profileId), remoteAssetSyncScheduler.scheduled)

            viewModel.updateOfflineLibrarySearchQuery("INTERVIEW")
            val assetSearch =
                withTimeout(5_000) {
                    viewModel.uiState.first { uiState ->
                        uiState.offlineLibrarySearchQuery == "INTERVIEW" &&
                            uiState.syncedAssetMatchCount == 1 &&
                            uiState.localRecordingMatchCount == 0
                    }
                }
            assertEquals("Synced interview", assetSearch.syncedAssets.single().title)
            assertEquals(emptyList<LocalRecordingSummary>(), assetSearch.localRecordings)
            assertEquals(TranscriptionPolicy.MANUAL, assetSearch.activeRecordingTranscriptionPolicy)

            viewModel.updateOfflineLibrarySearchQuery("FIELD-NOTE")
            val recordingSearch =
                withTimeout(5_000) {
                    viewModel.uiState.first { uiState ->
                        uiState.offlineLibrarySearchQuery == "FIELD-NOTE" &&
                            uiState.syncedAssetMatchCount == 0 &&
                            uiState.localRecordingMatchCount == 1
                    }
                }
            assertEquals(emptyList<SyncedAssetSummary>(), recordingSearch.syncedAssets)
            assertEquals("field-note.m4a", recordingSearch.localRecordings.single().fileName)
            viewModel.clearOfflineLibrarySearch()

            repository.save(secondProfile)
            credentialStore.writeSession(
                secondProfileId,
                StoredServerSession.legacy(
                    BearerCredential("va_second_profile_token_with_sufficient_entropy"),
                ),
            )
            withTimeout(5_000) {
                viewModel.uiState.first { it.serverProfiles.size == 2 }
            }
            viewModel.selectServerProfile(secondProfileId.value)

            val switched =
                withTimeout(5_000) {
                    viewModel.uiState.first {
                        it.activeServerProfileId == secondProfileId.value &&
                            it.localRecordings.singleOrNull()?.syncStatus == SyncUiStatus.FAILED
                    }
                }
            assertEquals(secondProfileId, activeProfile.value.value)
            assertEquals("Archived meeting", switched.syncedAssets.single().title)
            assertEquals("archive-note.m4a", switched.localRecordings.single().fileName)
            assertEquals(listOf(secondProfileId), remoteAssetSyncScheduler.refreshed)

            viewModel.refreshRemoteAssets()
            withTimeout(5_000) {
                viewModel.uiState.first { remoteAssetSyncScheduler.refreshed.size == 2 }
            }
            assertEquals(listOf(secondProfileId, secondProfileId), remoteAssetSyncScheduler.refreshed)

            viewModel.updateOfflineLibrarySearchQuery("RETRY_EXHAUSTED")
            val errorSearch =
                withTimeout(5_000) {
                    viewModel.uiState.first { uiState ->
                        uiState.offlineLibrarySearchQuery == "RETRY_EXHAUSTED" &&
                            uiState.localRecordingMatchCount == 1
                    }
                }
            assertEquals("archive-note.m4a", errorSearch.localRecordings.single().fileName)
            viewModel.clearOfflineLibrarySearch()

            viewModel.retryRecordingSync(secondRecordingId.value)

            val retried =
                withTimeout(5_000) {
                    viewModel.uiState.first {
                        it.activeServerProfileId == secondProfileId.value &&
                            it.localRecordings.singleOrNull()?.syncStatus == SyncUiStatus.PENDING
                    }
                }
            assertEquals(null, retried.localRecordings.single().errorCode)
            assertEquals(1, requireNotNull(syncTaskStore.find(secondRecordingId)).manualRetryGeneration)
            val retryRequest =
                withTimeout(5_000) {
                    syncScheduler.requests.first { requests -> requests.isNotEmpty() }.single()
                }
            assertEquals(secondRecordingId, retryRequest.recordingSessionId)
            assertEquals(secondProfileId, retryRequest.profile.id)
            assertEquals(true, retryRequest.force)
        }

    @Test
    fun assetMetadataEditorLoadsLatestVersionRefreshesCacheAndFailsClosedOnConflict() =
        runBlocking {
            val profileId = ServerProfileId.parse("82636d78-31f6-4349-899a-a87bb8bb6814")
            val assetId = "60000000-0000-4000-8000-000000000006"
            val collectionId = "70000000-0000-4000-8000-000000000007"
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            repository.save(
                ServerProfile.create(
                    id = profileId,
                    name = "Metadata server",
                    baseUrl = "https://example.test",
                    authenticationMode = AuthenticationMode.LOCAL_SESSION,
                    defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
                    defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
                    customCaPem = null,
                    certificateFingerprint = null,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ),
            )
            activeProfile.set(profileId)
            val incrementalSync =
                FakeIncrementalSyncStore(
                    mapOf(
                        profileId to
                            listOf(
                                CachedRemoteAsset(
                                    serverProfileId = profileId,
                                    assetId = assetId,
                                    collectionId = null,
                                    title = "Cached title",
                                    language = "en-US",
                                    status = "ready",
                                    durationMillis = 1_000,
                                    version = 3,
                                    changeSequence = 9,
                                    createdAtEpochMillis = 1_721_113_200_000,
                                    updatedAtEpochMillis = 1_721_113_200_000,
                                    trashedAtEpochMillis = null,
                                ),
                            ),
                    ),
                )
            val metadataEditor = FakeAssetMetadataEditor(incrementalSync)
            metadataEditor.loadedSession =
                AssetMetadataEditSession(
                    serverProfileId = profileId,
                    assetId = assetId,
                    title = "Latest title",
                    language = "en-GB",
                    collectionId = null,
                    version = 4,
                    expectedEntityTag = "\"4\"",
                )
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = incrementalSync,
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = FakeCredentialStore(),
                    authenticator = FakeAuthenticator(),
                    assetMetadataEditor = metadataEditor,
                )
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.syncedAssets.singleOrNull()?.id == assetId }
            }

            viewModel.startAssetMetadataEdit(assetId)
            val loaded =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.assetMetadataEditor?.status == AssetMetadataEditorStatus.EDITING
                    }
                }.assetMetadataEditor
            assertEquals("Latest title", loaded?.title)
            assertEquals(4L, loaded?.version)

            viewModel.updateAssetMetadataTitle("Android title")
            viewModel.updateAssetMetadataLanguage("zh-CN")
            viewModel.updateAssetMetadataCollectionId(collectionId)
            viewModel.saveAssetMetadata()
            viewModel.saveAssetMetadata()
            val saved =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.assetMetadataEditor?.status == AssetMetadataEditorStatus.SAVED &&
                            state.syncedAssets.singleOrNull()?.version == 5L
                    }
                }
            assertEquals("Android title", saved.syncedAssets.single().title)
            assertEquals("zh-CN", saved.syncedAssets.single().language)
            assertEquals(collectionId, saved.syncedAssets.single().collectionId)
            assertEquals(9L, incrementalSync.current(profileId, assetId)?.changeSequence)
            assertEquals(collectionId, metadataEditor.saveRequests.single().collectionId)

            viewModel.closeAssetMetadataEditor()
            metadataEditor.loadedSession =
                metadataEditor.loadedSession?.copy(
                    title = "Server version six",
                    version = 6,
                    expectedEntityTag = "\"6\"",
                )
            viewModel.startAssetMetadataEdit(assetId)
            withTimeout(5_000) {
                viewModel.uiState.first { state ->
                    state.assetMetadataEditor?.status == AssetMetadataEditorStatus.EDITING &&
                        state.assetMetadataEditor.version == 6L
                }
            }
            metadataEditor.saveFailure =
                VoiceAssetApiException(409, "conflict", null, "asset version changed")
            viewModel.updateAssetMetadataTitle("Must not win")
            viewModel.saveAssetMetadata()
            val conflict =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.assetMetadataEditor?.status == AssetMetadataEditorStatus.FAILED
                    }
                }
            assertEquals(AssetMetadataEditorError.CONFLICT, conflict.assetMetadataEditor?.error)
            assertEquals("Android title", conflict.syncedAssets.single().title)

            metadataEditor.saveFailure = null
            metadataEditor.loadedSession =
                metadataEditor.loadedSession?.copy(
                    title = "Server winner",
                    version = 7,
                    expectedEntityTag = "\"7\"",
                )
            viewModel.reloadAssetMetadata()
            val reloaded =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.assetMetadataEditor?.status == AssetMetadataEditorStatus.EDITING &&
                            state.assetMetadataEditor.version == 7L
                    }
                }
            assertEquals("Server winner", reloaded.assetMetadataEditor?.title)
        }

    @Test
    fun insecureOriginIsRejectedBeforePersistence() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = FakeCredentialStore(),
                    authenticator = FakeAuthenticator(),
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                )
            viewModel.updateServerName("Insecure")
            viewModel.updateServerUrl("http://example.test")
            viewModel.updateServerEmail("owner@example.test")
            viewModel.updateServerPassword("test-password")

            viewModel.saveServerProfile()

            val state =
                withTimeout(5_000) {
                    viewModel.uiState.first { it.serverFormError != null }
                }
            assertEquals(ServerProfileFormError.INVALID_URL, state.serverFormError)
            assertEquals(emptyList<ServerProfile>(), repository.value.value)
            assertNull(activeProfile.value.value)
        }

    @Test
    fun manualPoliciesExposeAndEnqueueSeparateUploadAndTranscriptionActions() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val profileId = ServerProfileId.parse("82000000-0000-4000-8000-000000000008")
            val uploadRecordingId = RecordingSessionId.parse("83000000-0000-4000-8000-000000000008")
            val transcriptionRecordingId = RecordingSessionId.parse("84000000-0000-4000-8000-000000000008")
            val profile =
                ServerProfile.create(
                    id = profileId,
                    name = "Manual server",
                    baseUrl = "https://manual.example.test",
                    authenticationMode = AuthenticationMode.LOCAL_SESSION,
                    defaultUploadPolicy = UploadPolicy.ANY_NETWORK,
                    defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
                    customCaPem = null,
                    certificateFingerprint = null,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                )
            repository.save(profile)
            activeProfile.set(profileId)
            val credentials = FakeCredentialStore()
            credentials.writeSession(
                profileId,
                StoredServerSession.legacy(
                    BearerCredential("va_manual_actions_token_with_sufficient_entropy"),
                ),
            )
            val recordings =
                FakeRecordingStore(
                    listOf(
                        savedRecording(
                            uploadRecordingId,
                            profileId,
                            "manual-upload.m4a",
                            uploadPolicyOverride = UploadPolicy.MANUAL,
                        ),
                        savedRecording(
                            transcriptionRecordingId,
                            profileId,
                            "manual-transcription.m4a",
                            transcriptionPolicyOverride = TranscriptionPolicy.MANUAL,
                        ),
                    ),
                )
            val syncTasks =
                FakeSyncTaskStore(
                    listOf(
                        SyncTask.restore(
                            recordingSessionId = transcriptionRecordingId,
                            serverProfileId = profileId,
                            stage = SyncStage.UPLOAD_COMPLETED,
                            assetId = "85000000-0000-4000-8000-000000000008",
                            uploadId = "86000000-0000-4000-8000-000000000008",
                            transcriptionJobId = null,
                            uploadedBytes = 1_024,
                            totalBytes = 1_024,
                            attemptCount = 0,
                            lastErrorCode = null,
                            blockReason = SyncBlockReason.NONE,
                            createdAtEpochMillis = 2,
                            updatedAtEpochMillis = 3,
                        ),
                    ),
                )
            val scheduler = FakeRecordingSyncEnqueuer()
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = recordings,
                    syncTasks = syncTasks,
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = scheduler,
                    credentials = credentials,
                    authenticator = FakeAuthenticator(),
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                )

            val state =
                withTimeout(5_000) {
                    viewModel.uiState.first { uiState -> uiState.localRecordings.size == 2 }
                }
            val uploadRecording = state.localRecordings.single { it.id == uploadRecordingId.value }
            val transcriptionRecording = state.localRecordings.single { it.id == transcriptionRecordingId.value }
            assertEquals(UploadPolicy.MANUAL, uploadRecording.uploadPolicy)
            assertEquals(true, uploadRecording.hasUploadPolicyOverride)
            assertEquals(true, uploadRecording.canStartUpload)
            assertEquals(TranscriptionPolicy.MANUAL, transcriptionRecording.transcriptionPolicy)
            assertEquals(true, transcriptionRecording.hasTranscriptionPolicyOverride)
            assertEquals(
                true,
                transcriptionRecording.canStartTranscription,
            )

            viewModel.updateRecordingUploadPolicyOverride(UploadPolicy.CHARGING_AND_WIFI)
            viewModel.updateRecordingTranscriptionPolicyOverride(TranscriptionPolicy.DISABLED)
            val overrides =
                withTimeout(5_000) {
                    viewModel.uiState.first { uiState ->
                        uiState.recordingUploadPolicyOverride == UploadPolicy.CHARGING_AND_WIFI &&
                            uiState.recordingTranscriptionPolicyOverride == TranscriptionPolicy.DISABLED
                    }
                }
            assertEquals(UploadPolicy.CHARGING_AND_WIFI, overrides.recordingUploadPolicyOverride)
            assertEquals(TranscriptionPolicy.DISABLED, overrides.recordingTranscriptionPolicyOverride)

            viewModel.startRecordingUpload(uploadRecordingId.value)
            viewModel.startRecordingTranscription(transcriptionRecordingId.value)

            val uploadRequest =
                withTimeout(5_000) {
                    scheduler.requests.first { requests -> requests.isNotEmpty() }.single()
                }
            val transcriptionRequest =
                withTimeout(5_000) {
                    scheduler.transcriptionRequests.first { requests -> requests.isNotEmpty() }.single()
                }
            assertEquals(uploadRecordingId, uploadRequest.recordingSessionId)
            assertEquals(transcriptionRecordingId, transcriptionRequest.recordingSessionId)
            assertEquals(true, uploadRequest.force)
            assertEquals(true, transcriptionRequest.force)
        }

    @Test
    fun mobileAdministrationLoadsAndAppliesVersionedProviderState() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val profileId = ServerProfileId.parse("82000000-0000-4000-8000-000000000009")
            repository.save(
                ServerProfile.create(
                    id = profileId,
                    name = "Administration server",
                    baseUrl = "https://administration.example.test",
                    authenticationMode = AuthenticationMode.LOCAL_SESSION,
                    defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
                    defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
                    customCaPem = null,
                    certificateFingerprint = null,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ),
            )
            activeProfile.set(profileId)
            val administration = FakeMobileAdministration(profileId)
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = FakeCredentialStore(),
                    authenticator = FakeAuthenticator(),
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                    mobileAdministration = administration,
                )
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.activeServerProfileId == profileId.value }
            }

            viewModel.refreshMobileAdministration()

            val loaded =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.mobileAdministration.status == MobileAdministrationStatus.READY
                    }
                }.mobileAdministration
            assertEquals(4L, loaded.systemStatus?.assetCount)
            assertEquals("mock_transcribe", loaded.jobs.single().kind)
            assertEquals(true, loaded.jobs.single().retryable)
            assertEquals(ProviderProfileState.DISABLED, loaded.providers.single().state)

            viewModel.retryMobileAdministrationJob(ADMIN_JOB_ID)

            val retried =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        val job = state.mobileAdministration.jobs.singleOrNull()
                        job?.state == "queued" && state.mobileAdministration.busyJobId == null
                    }
                }.mobileAdministration
            assertEquals(4, retried.jobs.single().maxAttempts)
            assertEquals(false, retried.jobs.single().retryable)
            assertEquals(1L, retried.systemStatus?.queuedJobCount)
            assertEquals(0L, retried.systemStatus?.failedJobCount)
            assertEquals(listOf(ADMIN_JOB_ID), administration.jobRetries)

            viewModel.setMobileProviderProfileEnabled(ASR_PROFILE_ID, enabled = true)

            val updated =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        val provider = state.mobileAdministration.providers.singleOrNull()
                        provider?.version == 2L &&
                            state.mobileAdministration.busyProviderProfileId == null
                    }
                }.mobileAdministration
            assertEquals(ProviderProfileState.ENABLED, updated.providers.single().state)
            assertEquals(2L, updated.systemStatus?.enabledAsrCount)
            assertEquals(
                listOf(ProviderUpdateRequest(ProviderProfileFamily.ASR, ASR_PROFILE_ID, 1, ProviderProfileState.ENABLED)),
                administration.updates,
            )

            viewModel.checkMobileProviderProfileHealth(ASR_PROFILE_ID)

            val health =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        val provider = state.mobileAdministration.providers.singleOrNull()
                        provider?.healthStatus == ProviderHealthStatus.HEALTHY
                    }
                }.mobileAdministration.providers.single()
            assertEquals("2026-07-16T08:03:00Z", health.healthCheckedAt)
            assertEquals(
                listOf(ProviderHealthRequest(ProviderProfileFamily.ASR, ASR_PROFILE_ID)),
                administration.healthChecks,
            )
        }

    @Test
    fun deviceSessionsRequireConfirmationAndCurrentRevocationSignsOutLocally() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val profileId = ServerProfileId.parse("87000000-0000-4000-8000-000000000009")
            repository.save(
                ServerProfile.create(
                    id = profileId,
                    name = "Device session server",
                    baseUrl = "https://sessions.example.test",
                    authenticationMode = AuthenticationMode.LOCAL_SESSION,
                    defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
                    defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
                    customCaPem = null,
                    certificateFingerprint = null,
                    createdAtEpochMillis = 1,
                    updatedAtEpochMillis = 1,
                ),
            )
            activeProfile.set(profileId)
            val deviceSessions = FakePersonalDeviceSessions(profileId)
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = FakeCredentialStore(),
                    authenticator = FakeAuthenticator(),
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                    personalDeviceSessions = deviceSessions,
                )
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.activeServerProfileId == profileId.value }
            }

            viewModel.refreshDeviceSessions()

            val loaded =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.deviceSessions.status == DeviceSessionsStatus.READY
                    }
                }.deviceSessions
            assertEquals(listOf(CURRENT_DEVICE_SESSION_ID, REMOTE_DEVICE_SESSION_ID), loaded.items.map { it.id })

            viewModel.requestDeviceSessionRevocation(REMOTE_DEVICE_SESSION_ID)
            val pending =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.deviceSessions.pendingRevocationId == REMOTE_DEVICE_SESSION_ID
                    }
                }.deviceSessions
            assertEquals(emptyList<String>(), deviceSessions.revocations)
            assertEquals(null, pending.revokingId)

            viewModel.confirmDeviceSessionRevocation()
            val remoteRevoked =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.deviceSessions.status == DeviceSessionsStatus.READY &&
                            state.deviceSessions.items.size == 1 &&
                            state.deviceSessions.revokingId == null
                    }
                }.deviceSessions
            assertEquals(listOf(CURRENT_DEVICE_SESSION_ID), remoteRevoked.items.map { it.id })
            assertEquals(listOf(REMOTE_DEVICE_SESSION_ID), deviceSessions.revocations)

            viewModel.requestDeviceSessionRevocation(CURRENT_DEVICE_SESSION_ID)
            viewModel.confirmDeviceSessionRevocation()
            val signedOut =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.deviceSessions.error == DeviceSessionsError.AUTHENTICATION_REQUIRED
                    }
                }.deviceSessions
            assertEquals(emptyList<DeviceSessionSummary>(), signedOut.items)
            assertEquals(
                listOf(REMOTE_DEVICE_SESSION_ID, CURRENT_DEVICE_SESSION_ID),
                deviceSessions.revocations,
            )
        }

    @Test
    fun reconnectReplacesTheSameProfilesSessionAndClearsPasswordBeforeNetworkCompletion() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val credentialStore = FakeCredentialStore()
            val remoteScheduler = FakeRemoteAssetSyncScheduler()
            val profileId = ServerProfileId.parse("93000000-0000-4000-8000-000000000009")
            repository.save(mainViewModelServerProfile(profileId))
            activeProfile.set(profileId)
            val authenticator = BlockingAuthenticator()
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = credentialStore,
                    authenticator = authenticator,
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                    remoteAssetSyncScheduler = remoteScheduler,
                )
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.activeServerProfileId == profileId.value }
            }

            viewModel.updateSessionReconnectEmail(" owner@example.test ")
            viewModel.updateSessionReconnectPassword("new-password")
            viewModel.reconnectActiveServerProfile()

            withTimeout(5_000) { authenticator.started.await() }
            val submitting =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.serverSessionReconnect.status == ServerSessionReconnectStatus.SUBMITTING
                    }
                }.serverSessionReconnect
            assertEquals(SecretInput.EMPTY, submitting.password)
            assertEquals("owner@example.test", authenticator.email)
            assertEquals("new-password", authenticator.password)

            authenticator.release.complete(Unit)
            val reconnected =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.serverSessionReconnect.status == ServerSessionReconnectStatus.SUCCEEDED
                    }
                }.serverSessionReconnect
            assertEquals("", reconnected.email)
            assertEquals(SecretInput.EMPTY, reconnected.password)
            assertEquals(listOf(profileId), repository.value.value.map(ServerProfile::id))
            assertEquals(TEST_CREDENTIAL, credentialStore.readSession(profileId)?.accessCredential?.value)
            assertEquals(listOf(profileId), remoteScheduler.refreshed)
        }

    @Test
    fun rejectedReconnectKeepsTheExistingSessionAndClearsTheAttemptedPassword() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val credentialStore = FakeCredentialStore()
            val profileId = ServerProfileId.parse("94000000-0000-4000-8000-000000000009")
            repository.save(mainViewModelServerProfile(profileId))
            activeProfile.set(profileId)
            val existingSession =
                StoredServerSession.fromLogin(
                    mainViewModelLoginResult("va_existing_session_token_123456"),
                )
            credentialStore.writeSession(profileId, existingSession)
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = credentialStore,
                    authenticator =
                        FakeAuthenticator(
                            VoiceAssetApiException(401, "unauthorized", null, "login rejected"),
                        ),
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                )
            withTimeout(5_000) {
                viewModel.uiState.first { state -> state.activeServerProfileId == profileId.value }
            }

            viewModel.updateSessionReconnectEmail("owner@example.test")
            viewModel.updateSessionReconnectPassword("wrong-password")
            viewModel.reconnectActiveServerProfile()

            val failed =
                withTimeout(5_000) {
                    viewModel.uiState.first { state ->
                        state.serverSessionReconnect.error == ServerSessionReconnectError.AUTHENTICATION_FAILED
                    }
                }.serverSessionReconnect
            assertEquals(SecretInput.EMPTY, failed.password)
            assertEquals("va_existing_session_token_123456", credentialStore.readSession(profileId)?.accessCredential?.value)
        }

    @Test
    fun rejectedLoginDoesNotPersistProfileOrCredential() =
        runBlocking {
            val repository = FakeServerProfileRepository()
            val activeProfile = FakeActiveProfileStore()
            val credentialStore = FakeCredentialStore()
            val profileId = ServerProfileId.parse("82636d78-31f6-4349-899a-a87bb8bb6814")
            val viewModel =
                MainViewModel(
                    profiles = repository,
                    activeProfile = activeProfile,
                    recordings = FakeRecordingStore(),
                    syncTasks = FakeSyncTaskStore(),
                    transcripts = FakeTranscriptStore(),
                    incrementalSync = FakeIncrementalSyncStore(),
                    syncScheduler = FakeRecordingSyncEnqueuer(),
                    credentials = credentialStore,
                    authenticator =
                        FakeAuthenticator(
                            VoiceAssetApiException(401, "unauthorized", null, "login rejected"),
                        ),
                    assetMetadataEditor = FakeAssetMetadataEditor(FakeIncrementalSyncStore()),
                    idFactory = { profileId },
                )
            viewModel.updateServerName("Test server")
            viewModel.updateServerUrl("https://example.test")
            viewModel.updateServerEmail("owner@example.test")
            viewModel.updateServerPassword("test-password")

            viewModel.saveServerProfile()

            val state =
                withTimeout(5_000) {
                    viewModel.uiState.first { it.serverFormError != null }
                }
            assertEquals(ServerProfileFormError.AUTHENTICATION_FAILED, state.serverFormError)
            assertEquals(SecretInput.EMPTY, state.serverDraft.password)
            assertEquals(emptyList<ServerProfile>(), repository.value.value)
            assertNull(credentialStore.read(profileId))
            assertNull(activeProfile.value.value)
        }

    private class FakeServerProfileRepository(
        private val saveFailure: Exception? = null,
    ) : ServerProfileRepository {
        val value = MutableStateFlow<List<ServerProfile>>(emptyList())

        override fun observeAll(): Flow<List<ServerProfile>> = value

        override suspend fun find(id: ServerProfileId): ServerProfile? = value.value.firstOrNull { it.id == id }

        override suspend fun save(profile: ServerProfile) {
            saveFailure?.let { throw it }
            value.update { profiles -> profiles.filterNot { it.id == profile.id } + profile }
        }

        override suspend fun delete(id: ServerProfileId) {
            value.update { profiles -> profiles.filterNot { it.id == id } }
        }
    }

    private class FakeAuthenticator(
        private val failure: Exception? = null,
    ) : ServerProfileAuthenticator {
        var email: String? = null

        override fun authenticate(
            profile: ServerProfile,
            email: String,
            password: String,
        ): LoginResult {
            this.email = email
            check(password.isNotEmpty())
            failure?.let { throw it }
            return mainViewModelLoginResult(TEST_CREDENTIAL)
        }
    }

    private class FakePairingAuthenticator : DevicePairingAuthenticator {
        var payload: String? = null

        override fun authenticate(
            payload: String,
            deviceName: String,
        ): DevicePairingResult {
            this.payload = payload
            assertEquals("VoiceAsset Android", deviceName)
            return DevicePairingResult(
                origin = "https://api.getio.net:10443",
                login =
                    LoginResult(
                        session =
                            WebSession(
                                expiresAt = "2099-07-18T16:00:00Z",
                                refreshExpiresAt = "2099-08-17T16:00:00Z",
                                user =
                                    Principal(
                                        id = "10000000-0000-4000-8000-000000000001",
                                        workspaceId = "20000000-0000-4000-8000-000000000002",
                                        role = "owner",
                                        email = "owner@example.com",
                                        scopes = listOf("assets:read", "assets:write"),
                                    ),
                            ),
                        credential = BearerCredential(TEST_CREDENTIAL),
                        refreshCredential = RefreshCredential("va_rft_${"r".repeat(43)}"),
                    ),
            )
        }
    }

    private class BlockingAuthenticator : ServerProfileAuthenticator {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var email: String? = null
        var password: String? = null

        override fun authenticate(
            profile: ServerProfile,
            email: String,
            password: String,
        ): LoginResult =
            runBlocking {
                this@BlockingAuthenticator.email = email
                this@BlockingAuthenticator.password = password
                started.complete(Unit)
                release.await()
                mainViewModelLoginResult(TEST_CREDENTIAL)
            }
    }

    private class FakeCredentialStore : ServerCredentialStore {
        private val values = mutableMapOf<ServerProfileId, ByteArray>()
        private val sessions = mutableMapOf<ServerProfileId, StoredServerSession>()

        override suspend fun write(
            profileId: ServerProfileId,
            credential: ByteArray,
        ) {
            values[profileId] = credential.copyOf()
        }

        override suspend fun read(profileId: ServerProfileId): ByteArray? = values[profileId]?.copyOf()

        override suspend fun writeSession(
            profileId: ServerProfileId,
            session: StoredServerSession,
        ) {
            sessions[profileId] = session
            write(profileId, session.accessCredential.value.encodeToByteArray())
        }

        override suspend fun readSession(profileId: ServerProfileId): StoredServerSession? = sessions[profileId]

        override suspend fun remove(profileId: ServerProfileId) {
            values.remove(profileId)?.fill(0)
            sessions.remove(profileId)
        }
    }

    private class FakeActiveProfileStore : ActiveProfileStore {
        val value = MutableStateFlow<ServerProfileId?>(null)

        override fun observe(): Flow<ServerProfileId?> = value

        override suspend fun set(profileId: ServerProfileId) {
            value.value = profileId
        }

        override suspend fun clear() {
            value.value = null
        }
    }

    private class FakeRecordingStore(
        initial: List<StoredRecording> = emptyList(),
    ) : RecordingStore {
        private val recordings = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<StoredRecording>> = recordings

        override suspend fun find(id: RecordingSessionId): StoredRecording? = recordings.value.firstOrNull { it.session.id == id }

        override suspend fun loadRecoverable(): List<StoredRecording> = emptyList()

        override suspend fun recoverSaved(
            recording: LocalRecording,
            updatedAtEpochMillis: Long,
        ) = Unit

        override suspend fun persist(
            state: RecordingState,
            updatedAtEpochMillis: Long,
        ) = Unit
    }

    private class FakeSyncTaskStore(
        initial: List<SyncTask> = emptyList(),
    ) : SyncTaskStore {
        private val tasks = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<SyncTask>> = tasks

        override suspend fun find(recordingSessionId: RecordingSessionId): SyncTask? =
            tasks.value.firstOrNull { task -> task.recordingSessionId == recordingSessionId }

        override suspend fun create(task: SyncTask): SyncTask {
            tasks.update { values -> values.filterNot { it.recordingSessionId == task.recordingSessionId } + task }
            return task
        }

        override suspend fun transition(
            recordingSessionId: RecordingSessionId,
            event: SyncEvent,
            updatedAtEpochMillis: Long,
        ): SyncTask {
            val existing = requireNotNull(find(recordingSessionId))
            val updated =
                SyncStateMachine.transition(
                    task = existing,
                    event = event,
                    updatedAtEpochMillis = maxOf(updatedAtEpochMillis, existing.updatedAtEpochMillis),
                )
            tasks.update { values ->
                values.filterNot { it.recordingSessionId == recordingSessionId } + updated
            }
            return updated
        }
    }

    private class FakeRecordingSyncEnqueuer : RecordingSyncEnqueuer {
        val requests = MutableStateFlow<List<RetryRequest>>(emptyList())
        val transcriptionRequests = MutableStateFlow<List<RetryRequest>>(emptyList())

        override fun enqueue(
            recordingSession: RecordingSession,
            profile: ServerProfile,
            force: Boolean,
        ): Boolean {
            requests.update { values -> values + RetryRequest(recordingSession, profile, force) }
            return true
        }

        override fun enqueueTranscription(
            recordingSession: RecordingSession,
            profile: ServerProfile,
            force: Boolean,
        ): Boolean {
            transcriptionRequests.update { values -> values + RetryRequest(recordingSession, profile, force) }
            return true
        }
    }

    private class FakeRemoteAssetSyncScheduler : RemoteAssetSyncScheduler {
        val scheduled = mutableListOf<ServerProfileId>()
        val refreshed = mutableListOf<ServerProfileId>()

        override fun schedule(serverProfileId: ServerProfileId) {
            scheduled += serverProfileId
        }

        override fun refresh(serverProfileId: ServerProfileId) {
            refreshed += serverProfileId
        }
    }

    private inner class FakeMobileAdministration(
        private val serverProfileId: ServerProfileId,
    ) : MobileAdministration {
        val updates = mutableListOf<ProviderUpdateRequest>()
        val healthChecks = mutableListOf<ProviderHealthRequest>()
        val jobRetries = mutableListOf<String>()

        override suspend fun load(serverProfileId: ServerProfileId): MobileAdministrationSnapshot {
            check(serverProfileId == this.serverProfileId)
            return mobileAdministrationSnapshot(serverProfileId)
        }

        override suspend fun setProviderProfileState(
            serverProfileId: ServerProfileId,
            family: ProviderProfileFamily,
            providerProfileId: String,
            expectedVersion: Long,
            state: ProviderProfileState,
        ): MobileAdministrationProviderProfile {
            check(serverProfileId == this.serverProfileId)
            updates += ProviderUpdateRequest(family, providerProfileId, expectedVersion, state)
            val previous = mobileAdministrationSnapshot(serverProfileId).providers.single()
            return previous.copy(profile = previous.profile.copy(state = state, version = expectedVersion + 1))
        }

        override suspend fun checkProviderProfileHealth(
            serverProfileId: ServerProfileId,
            family: ProviderProfileFamily,
            providerProfileId: String,
        ): ProviderHealth {
            check(serverProfileId == this.serverProfileId)
            healthChecks += ProviderHealthRequest(family, providerProfileId)
            return ProviderHealth(
                profileId = providerProfileId,
                status = ProviderHealthStatus.HEALTHY,
                checkedAt = "2026-07-16T08:03:00Z",
            )
        }

        override suspend fun retryJob(
            serverProfileId: ServerProfileId,
            jobId: String,
        ): AdministrationJob {
            check(serverProfileId == this.serverProfileId)
            jobRetries += jobId
            return mobileAdministrationSnapshot(serverProfileId).jobs.single().copy(
                state = "queued",
                maxAttempts = 4,
                retryable = false,
                lastErrorCode = null,
            )
        }
    }

    private class FakePersonalDeviceSessions(
        private val serverProfileId: ServerProfileId,
    ) : PersonalDeviceSessions {
        private var items = mainViewModelDeviceSessions()
        val revocations = mutableListOf<String>()

        override suspend fun load(serverProfileId: ServerProfileId): PersonalDeviceSessionsSnapshot {
            check(serverProfileId == this.serverProfileId)
            return PersonalDeviceSessionsSnapshot(serverProfileId, items)
        }

        override suspend fun revoke(
            serverProfileId: ServerProfileId,
            deviceSessionId: String,
        ): DeviceSession {
            check(serverProfileId == this.serverProfileId)
            val target = requireNotNull(items.firstOrNull { item -> item.id == deviceSessionId })
            revocations += target.id
            items = items.filterNot { item -> item.id == target.id }
            return target
        }
    }

    private data class ProviderUpdateRequest(
        val family: ProviderProfileFamily,
        val providerProfileId: String,
        val expectedVersion: Long,
        val state: ProviderProfileState,
    )

    private data class ProviderHealthRequest(
        val family: ProviderProfileFamily,
        val providerProfileId: String,
    )

    private fun mobileAdministrationSnapshot(serverProfileId: ServerProfileId): MobileAdministrationSnapshot =
        MobileAdministrationSnapshot(
            serverProfileId = serverProfileId,
            systemStatus =
                AdministrationSystemStatus(
                    generatedAt = "2026-07-16T08:02:00Z",
                    activeUsers = 2,
                    assets = AdministrationAssetStatus(4, 3, 1, 0, 0, 65_000),
                    storage = AdministrationStorageStatus(3, 2_048),
                    transcripts = AdministrationTranscriptStatus(2, 3),
                    jobs = AdministrationJobStatus(1, 0, 0, 0, 0, 1, 0),
                    providers = AdministrationProviderStatus(1, 1),
                ),
            jobs =
                listOf(
                    AdministrationJob(
                        id = ADMIN_JOB_ID,
                        createdBy = "84000000-0000-4000-8000-000000000009",
                        kind = "mock_transcribe",
                        state = "failed",
                        attempts = 3,
                        maxAttempts = 3,
                        retryable = true,
                        lastErrorCode = "provider_unavailable",
                        availableAt = "2026-07-16T08:00:00Z",
                        createdAt = "2026-07-16T08:00:00Z",
                        updatedAt = "2026-07-16T08:01:00Z",
                    ),
                ),
            providers =
                listOf(
                    MobileAdministrationProviderProfile(
                        family = ProviderProfileFamily.ASR,
                        profile =
                            ProviderProfile(
                                id = ASR_PROFILE_ID,
                                workspaceId = "85000000-0000-4000-8000-000000000009",
                                providerId = "mock_asr",
                                displayName = "Mock ASR",
                                config = buildJsonObject { put("model", "mock-v1") },
                                state = ProviderProfileState.DISABLED,
                                priority = 100,
                                version = 1,
                                secretConfigured = true,
                                createdAt = "2026-07-16T08:00:00Z",
                                updatedAt = "2026-07-16T08:01:00Z",
                            ),
                    ),
                ),
        )

    private data class RetryRequest(
        val recordingSession: RecordingSession,
        val profile: ServerProfile,
        val force: Boolean,
    ) {
        val recordingSessionId: RecordingSessionId
            get() = recordingSession.id
    }

    private fun savedRecording(
        recordingSessionId: RecordingSessionId,
        profileId: ServerProfileId,
        fileName: String,
        uploadPolicyOverride: UploadPolicy? = null,
        transcriptionPolicyOverride: TranscriptionPolicy? = null,
    ): StoredRecording =
        StoredRecording(
            session =
                RecordingSession(
                    id = recordingSessionId,
                    fileName = fileName,
                    startedAtEpochMillis = 1,
                    serverProfileId = profileId,
                    uploadPolicyOverride = uploadPolicyOverride,
                    transcriptionPolicyOverride = transcriptionPolicyOverride,
                ),
            status = StoredRecordingStatus.SAVED,
            recording =
                LocalRecording(
                    sessionId = recordingSessionId,
                    fileName = fileName,
                    durationMillis = 1_000,
                    sizeBytes = 1_024,
                    sha256 = "ab".repeat(32),
                    stoppedAtEpochMillis = 2,
                ),
            errorCode = null,
            updatedAtEpochMillis = 2,
        )

    private class FakeTranscriptStore : TranscriptStore {
        private val transcripts = MutableStateFlow<List<LocalTranscript>>(emptyList())

        override fun observeAll(): Flow<List<LocalTranscript>> = transcripts

        override suspend fun find(recordingSessionId: RecordingSessionId): LocalTranscript? =
            transcripts.value.firstOrNull { transcript -> transcript.recordingSessionId == recordingSessionId }

        override suspend fun save(transcript: LocalTranscript) {
            transcripts.update { values ->
                values.filterNot { value -> value.recordingSessionId == transcript.recordingSessionId } + transcript
            }
        }
    }

    private class FakeIncrementalSyncStore(
        initialAssets: Map<ServerProfileId, List<CachedRemoteAsset>> = emptyMap(),
    ) : IncrementalSyncStore {
        private val assets =
            initialAssets
                .mapValues { (_, values) -> MutableStateFlow(values) }
                .toMutableMap()

        override fun observeAssets(serverProfileId: ServerProfileId): Flow<List<CachedRemoteAsset>> =
            assets.getOrPut(serverProfileId) { MutableStateFlow(emptyList()) }

        override suspend fun checkpoint(serverProfileId: ServerProfileId): IncrementalSyncCheckpoint? = null

        override suspend fun refreshAsset(
            serverProfileId: ServerProfileId,
            asset: Asset,
        ): CachedRemoteAsset {
            val values = assets.getOrPut(serverProfileId) { MutableStateFlow(emptyList()) }
            val existing = requireNotNull(values.value.firstOrNull { cached -> cached.assetId == asset.id })
            val refreshed =
                existing.copy(
                    collectionId = asset.collectionId,
                    title = asset.title,
                    language = asset.language,
                    status = asset.status,
                    durationMillis = asset.durationMillis,
                    version = asset.version,
                    createdAtEpochMillis = Instant.parse(asset.createdAt).toEpochMilli(),
                    updatedAtEpochMillis = Instant.parse(asset.updatedAt).toEpochMilli(),
                    trashedAtEpochMillis = null,
                )
            values.update { cached -> cached.filterNot { it.assetId == asset.id } + refreshed }
            return refreshed
        }

        override suspend fun mergeCatalogPage(
            serverProfileId: ServerProfileId,
            page: AssetList,
        ): Int = error("not used by MainViewModel tests")

        fun current(
            serverProfileId: ServerProfileId,
            assetId: String,
        ): CachedRemoteAsset? = assets[serverProfileId]?.value?.firstOrNull { cached -> cached.assetId == assetId }

        override suspend fun applyPage(
            serverProfileId: ServerProfileId,
            expectedCursor: String?,
            page: SyncChangeList,
            appliedAtEpochMillis: Long,
        ): IncrementalSyncCheckpoint = error("not used by MainViewModel tests")
    }

    private class FakeAssetMetadataEditor(
        private val incrementalSync: IncrementalSyncStore,
    ) : AssetMetadataEditor {
        var loadedSession: AssetMetadataEditSession? = null
        var loadFailure: Exception? = null
        var saveFailure: Exception? = null
        val saveRequests = mutableListOf<AssetMetadataSaveRequest>()

        override suspend fun load(
            serverProfileId: ServerProfileId,
            assetId: String,
        ): AssetMetadataEditSession {
            loadFailure?.let { throw it }
            val session = requireNotNull(loadedSession) { "asset metadata load was not configured" }
            check(session.serverProfileId == serverProfileId && session.assetId == assetId)
            return session
        }

        override suspend fun save(
            session: AssetMetadataEditSession,
            title: String,
            language: String,
            collectionId: String?,
        ): CachedRemoteAsset {
            saveRequests += AssetMetadataSaveRequest(title, language, collectionId)
            saveFailure?.let { throw it }
            return incrementalSync.refreshAsset(
                session.serverProfileId,
                Asset(
                    id = session.assetId,
                    workspaceId = "80000000-0000-4000-8000-000000000008",
                    collectionId = collectionId,
                    title = title.trim(),
                    language = language,
                    status = "ready",
                    durationMillis = 1_000,
                    version = session.version + 1,
                    createdAt = "2026-07-16T08:00:00Z",
                    updatedAt = "2026-07-16T08:01:00Z",
                ),
            )
        }
    }

    private data class AssetMetadataSaveRequest(
        val title: String,
        val language: String,
        val collectionId: String?,
    )

    private companion object {
        const val PAIRING_PAYLOAD = "voiceasset://pair?one-time-secret"
        const val TEST_CREDENTIAL = "va_test_session_1234567890"
        const val ASR_PROFILE_ID = "86000000-0000-4000-8000-000000000009"
        const val ADMIN_JOB_ID = "83000000-0000-4000-8000-000000000009"
        const val CURRENT_DEVICE_SESSION_ID = "88000000-0000-4000-8000-000000000009"
        const val REMOTE_DEVICE_SESSION_ID = "89000000-0000-4000-8000-000000000009"
    }
}

private fun mainViewModelServerProfile(profileId: ServerProfileId): ServerProfile =
    ServerProfile.create(
        id = profileId,
        name = "Test server",
        baseUrl = "https://example.test",
        authenticationMode = AuthenticationMode.LOCAL_SESSION,
        defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
        defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
        customCaPem = null,
        certificateFingerprint = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

private fun mainViewModelDeviceSessions(): List<DeviceSession> =
    listOf(
        DeviceSession(
            id = "88000000-0000-4000-8000-000000000009",
            deviceName = "Pixel 9",
            current = true,
            createdAt = "2026-07-17T00:00:00Z",
            lastSeenAt = "2026-07-18T05:30:00Z",
            expiresAt = "2026-07-18T17:30:00Z",
            refreshExpiresAt = "2026-08-17T05:30:00Z",
        ),
        DeviceSession(
            id = "89000000-0000-4000-8000-000000000009",
            deviceName = "Tablet",
            current = false,
            createdAt = "2026-07-16T00:00:00Z",
            lastSeenAt = "2026-07-17T05:30:00Z",
            expiresAt = "2026-07-17T17:30:00Z",
            refreshExpiresAt = "2026-08-16T05:30:00Z",
        ),
    )

private fun mainViewModelLoginResult(accessCredential: String): LoginResult =
    LoginResult(
        session =
            WebSession(
                expiresAt = "2099-07-18T16:00:00Z",
                refreshExpiresAt = "2099-08-17T16:00:00Z",
                user =
                    Principal(
                        id = "10000000-0000-4000-8000-000000000001",
                        workspaceId = "20000000-0000-4000-8000-000000000002",
                        role = "owner",
                        email = "owner@example.com",
                        scopes = listOf("assets:read", "assets:write"),
                    ),
            ),
        credential = BearerCredential(accessCredential),
        refreshCredential = RefreshCredential("va_rft_${"r".repeat(43)}"),
    )
