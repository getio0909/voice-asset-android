package com.voiceasset.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.voiceasset.android.administration.MobileAdministration
import com.voiceasset.android.administration.MobileAdministrationAuthenticationRequiredException
import com.voiceasset.android.administration.MobileAdministrationProfileUnavailableException
import com.voiceasset.android.administration.MobileAdministrationProviderProfile
import com.voiceasset.android.administration.MobileAdministrationSnapshot
import com.voiceasset.android.administration.MobileAdministrationUnavailableException
import com.voiceasset.android.asset.AssetMetadataAuthenticationRequiredException
import com.voiceasset.android.asset.AssetMetadataEditSession
import com.voiceasset.android.asset.AssetMetadataEditor
import com.voiceasset.android.asset.AssetMetadataProfileUnavailableException
import com.voiceasset.android.data.CachedRemoteAssetMissingException
import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.android.data.StoredRecording
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.android.data.SyncTaskStore
import com.voiceasset.android.data.TranscriptStore
import com.voiceasset.android.data.preferences.ActiveProfileStore
import com.voiceasset.android.security.AuthenticatedServerProfileReconnector
import com.voiceasset.android.security.CredentialStoreException
import com.voiceasset.android.security.PersonalDeviceSessionNotFoundException
import com.voiceasset.android.security.PersonalDeviceSessions
import com.voiceasset.android.security.PersonalDeviceSessionsAuthenticationRequiredException
import com.voiceasset.android.security.PersonalDeviceSessionsProfileUnavailableException
import com.voiceasset.android.security.PersonalDeviceSessionsSnapshot
import com.voiceasset.android.security.PersonalDeviceSessionsUnavailableException
import com.voiceasset.android.security.ServerCredentialStore
import com.voiceasset.android.security.ServerProfileReconnectProfileUnavailableException
import com.voiceasset.android.security.ServerProfileReconnector
import com.voiceasset.android.security.StartupSyncPolicy
import com.voiceasset.android.security.StoredServerSession
import com.voiceasset.android.sync.RecordingSyncEnqueuer
import com.voiceasset.android.sync.RemoteAssetSyncScheduler
import com.voiceasset.core.api.AdministrationJob
import com.voiceasset.core.api.DevicePairingAuthenticator
import com.voiceasset.core.api.InvalidPairingPayloadException
import com.voiceasset.core.api.ProviderHealth
import com.voiceasset.core.api.ProviderProfileState
import com.voiceasset.core.api.ServerProfileAuthenticator
import com.voiceasset.core.api.VoiceAssetApiException
import com.voiceasset.core.api.VoiceAssetConnectionException
import com.voiceasset.core.api.VoiceAssetProtocolException
import com.voiceasset.core.api.VoiceAssetTlsException
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.CertificateFingerprint
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.ServerOrigin
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.SyncBlockReason
import com.voiceasset.core.model.SyncEvent
import com.voiceasset.core.model.SyncStage
import com.voiceasset.core.model.SyncTask
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(
    private val profiles: ServerProfileRepository,
    private val activeProfile: ActiveProfileStore,
    private val recordings: RecordingStore,
    private val syncTasks: SyncTaskStore,
    transcripts: TranscriptStore,
    incrementalSync: IncrementalSyncStore,
    private val syncScheduler: RecordingSyncEnqueuer,
    private val credentials: ServerCredentialStore,
    private val authenticator: ServerProfileAuthenticator,
    private val profileReconnector: ServerProfileReconnector =
        AuthenticatedServerProfileReconnector(profiles, credentials, authenticator),
    private val pairingAuthenticator: DevicePairingAuthenticator = DevicePairingAuthenticator.UNAVAILABLE,
    private val assetMetadataEditor: AssetMetadataEditor,
    private val personalDeviceSessions: PersonalDeviceSessions = PersonalDeviceSessions.NONE,
    private val mobileAdministration: MobileAdministration = MobileAdministration.NONE,
    private val remoteAssetSyncScheduler: RemoteAssetSyncScheduler = RemoteAssetSyncScheduler.NONE,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> ServerProfileId = {
        ServerProfileId.parse(UUID.randomUUID().toString())
    },
) : ViewModel() {
    private val editor = MutableStateFlow(ServerProfileEditorState())
    private val assetMetadataRequestIds = AtomicLong()
    private val sessionReconnectRequestIds = AtomicLong()
    private val deviceSessionRequestIds = AtomicLong()
    private val mobileAdministrationRequestIds = AtomicLong()

    private val profileSelection =
        combine(
            profiles.observeAll(),
            activeProfile.observe(),
        ) { savedProfiles, selectedProfileId ->
            val activeProfileId =
                selectedProfileId
                    ?.takeIf { selected -> savedProfiles.any { profile -> profile.id == selected } }
                    ?: savedProfiles.firstOrNull()?.id
            ProfileSelection(savedProfiles, activeProfileId)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState =
        profileSelection
            .flatMapLatest { selection ->
                val savedProfiles = selection.savedProfiles
                val activeProfileId = selection.activeProfileId
                val cachedAssets =
                    activeProfileId?.let(incrementalSync::observeAssets)
                        ?: flowOf(emptyList())

                combine(
                    recordings.observeAll(),
                    syncTasks.observeAll(),
                    transcripts.observeAll(),
                    editor,
                    cachedAssets,
                ) { storedRecordings, storedSyncTasks, storedTranscripts, editorState, remoteAssets ->
                    val activeServerProfile =
                        savedProfiles.firstOrNull { profile -> profile.id == activeProfileId }
                    val profileRecordings =
                        storedRecordings.filter { recording ->
                            recording.session.serverProfileId == null ||
                                recording.session.serverProfileId == activeProfileId
                        }
                    val syncTasksByRecordingId = storedSyncTasks.associateBy(SyncTask::recordingSessionId)
                    val transcriptRecordingIds = storedTranscripts.mapTo(mutableSetOf()) { it.recordingSessionId }
                    val searchQuery = editorState.offlineLibrarySearchQuery.trim()
                    val matchingRecordings =
                        profileRecordings.filter { recording ->
                            val syncTask = syncTasksByRecordingId[recording.session.id]
                            matchesOfflineLibrarySearch(
                                searchQuery,
                                recording.session.id.value,
                                recording.session.fileName,
                                recording.status.name,
                                recording.errorCode?.name,
                                syncTask?.stage?.name,
                                syncTask?.blockReason?.name,
                                syncTask?.lastErrorCode,
                                if (recording.session.id in transcriptRecordingIds) "transcript" else null,
                            )
                        }
                    val matchingRemoteAssets =
                        remoteAssets.filter { asset ->
                            matchesOfflineLibrarySearch(
                                searchQuery,
                                asset.assetId,
                                asset.collectionId,
                                asset.title,
                                asset.language,
                                asset.status,
                                if (asset.trashedAtEpochMillis != null) "trashed" else null,
                            )
                        }
                    val latestRecording =
                        profileRecordings.firstOrNull { recording -> !recording.status.isTerminal() }
                            ?: profileRecordings.firstOrNull()
                    val latestTranscript =
                        latestRecording?.let { recording ->
                            storedTranscripts.firstOrNull { transcript ->
                                transcript.recordingSessionId == recording.session.id
                            }
                        }
                    AppUiState(
                        initializationStatus = InitializationStatus.INITIALIZED,
                        serverStatus =
                            if (savedProfiles.isEmpty()) {
                                ServerStatus.NOT_CONFIGURED
                            } else {
                                ServerStatus.CONFIGURED
                            },
                        serverProfiles =
                            savedProfiles.map { profile ->
                                ServerProfileSummary(
                                    id = profile.id.value,
                                    name = profile.name,
                                    origin = profile.origin.value,
                                    uploadPolicy = profile.defaultUploadPolicy,
                                    transcriptionPolicy = profile.defaultTranscriptionPolicy,
                                    isActive = profile.id == activeProfileId,
                                )
                            },
                        activeServerProfileId = activeProfileId?.value,
                        serverDraft = editorState.draft,
                        isSavingServer = editorState.isSaving,
                        serverFormError = editorState.error,
                        recordingStatus = recordingStatus(latestRecording),
                        recordingError = latestRecording?.errorCode?.name,
                        recordingUploadPolicyOverride = editorState.recordingUploadPolicyOverride,
                        recordingTranscriptionPolicyOverride = editorState.recordingTranscriptionPolicyOverride,
                        offlineLibrarySearchQuery = editorState.offlineLibrarySearchQuery,
                        localRecordingCount = profileRecordings.size,
                        localRecordingMatchCount = matchingRecordings.size,
                        localRecordings =
                            matchingRecordings.take(MAX_LOCAL_RECORDINGS_ON_HOME).map { recording ->
                                val syncTask = syncTasksByRecordingId[recording.session.id]
                                val hasTranscript = recording.session.id in transcriptRecordingIds
                                val recordingProfile =
                                    recording.session.serverProfileId?.let { profileId ->
                                        savedProfiles.firstOrNull { profile -> profile.id == profileId }
                                    }
                                val uploadPolicy = recordingProfile?.let(recording.session::effectiveUploadPolicy)
                                val transcriptionPolicy =
                                    recordingProfile?.let(recording.session::effectiveTranscriptionPolicy)
                                LocalRecordingSummary(
                                    id = recording.session.id.value,
                                    fileName = recording.session.fileName,
                                    recordingStatus = recording.status.toUiStatus(),
                                    durationMillis = recording.recording?.durationMillis,
                                    syncStatus = recording.syncStatus(syncTask),
                                    uploadedBytes = syncTask?.uploadedBytes ?: 0,
                                    totalBytes = syncTask?.totalBytes ?: (recording.recording?.sizeBytes ?: 0),
                                    hasTranscript = hasTranscript,
                                    errorCode = syncTask?.lastErrorCode ?: recording.errorCode?.name,
                                    uploadPolicy = uploadPolicy,
                                    transcriptionPolicy = transcriptionPolicy,
                                    hasUploadPolicyOverride = recording.session.uploadPolicyOverride != null,
                                    hasTranscriptionPolicyOverride =
                                        recording.session.transcriptionPolicyOverride != null,
                                    canStartUpload =
                                        uploadPolicy == UploadPolicy.MANUAL &&
                                            recording.status == StoredRecordingStatus.SAVED &&
                                            syncTask?.stage in setOf(null, SyncStage.QUEUED),
                                    canStartTranscription =
                                        transcriptionPolicy == TranscriptionPolicy.MANUAL &&
                                            syncTask?.stage == SyncStage.UPLOAD_COMPLETED &&
                                            syncTask.blockReason == SyncBlockReason.NONE &&
                                            !hasTranscript,
                                    canPlay = recording.recording != null,
                                    canExport = recording.recording != null,
                                )
                            },
                        transcriptRevisionId = latestTranscript?.revisionId,
                        transcriptLanguage = latestTranscript?.language,
                        transcriptText = latestTranscript?.text,
                        activeRecordingTranscriptionPolicy =
                            if (latestRecording == null) {
                                activeServerProfile?.defaultTranscriptionPolicy
                            } else {
                                latestRecording.session.serverProfileId?.let { profileId ->
                                    savedProfiles.firstOrNull { profile -> profile.id == profileId }?.let { profile ->
                                        latestRecording.session.effectiveTranscriptionPolicy(profile)
                                    }
                                }
                            },
                        syncedAssetCount = remoteAssets.size,
                        syncedAssetMatchCount = matchingRemoteAssets.size,
                        syncedAssets =
                            matchingRemoteAssets.take(MAX_SYNCED_ASSETS_ON_HOME).map { asset ->
                                SyncedAssetSummary(
                                    id = asset.assetId,
                                    title = asset.title,
                                    language = asset.language,
                                    status = asset.status,
                                    durationMillis = asset.durationMillis,
                                    version = asset.version,
                                    isTrashed = asset.trashedAtEpochMillis != null,
                                    collectionId = asset.collectionId,
                                )
                            },
                        assetMetadataEditor =
                            editorState.assetMetadataEdit
                                ?.takeIf { edit -> edit.serverProfileId == activeProfileId }
                                ?.toUiState(),
                        serverSessionReconnect =
                            editorState.sessionReconnect.toUiState(activeProfileId),
                        deviceSessions =
                            editorState.deviceSessions.toUiState(activeProfileId),
                        mobileAdministration =
                            editorState.mobileAdministration.toUiState(activeProfileId),
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = initialAppUiState(),
            )

    fun updateServerName(value: String) {
        updateDraft { draft -> draft.copy(name = value) }
    }

    fun updateServerUrl(value: String) {
        updateDraft { draft -> draft.copy(baseUrl = value) }
    }

    fun updateServerEmail(value: String) {
        updateDraft { draft -> draft.copy(email = value) }
    }

    fun updateServerPassword(value: String) {
        updateDraft { draft -> draft.copy(password = SecretInput.of(value)) }
    }

    fun updatePairingPayload(value: String) {
        updateDraft { draft ->
            draft.copy(pairingPayload = SecretInput.of(value.take(MAX_PAIRING_PAYLOAD_LENGTH)))
        }
    }

    fun updateSessionReconnectEmail(value: String) {
        updateSessionReconnect { current ->
            current.copy(email = value.take(MAX_EMAIL_LENGTH + 1))
        }
    }

    fun updateSessionReconnectPassword(value: String) {
        updateSessionReconnect { current ->
            current.copy(password = SecretInput.of(value.take(MAX_PASSWORD_LENGTH + 1)))
        }
    }

    fun updateCustomCaPem(value: String) {
        updateDraft { draft -> draft.copy(customCaPem = value) }
    }

    fun updateCertificateFingerprint(value: String) {
        updateDraft { draft -> draft.copy(certificateFingerprint = value) }
    }

    fun updateUploadPolicy(value: UploadPolicy) {
        updateDraft { draft -> draft.copy(uploadPolicy = value) }
    }

    fun updateTranscriptionPolicy(value: TranscriptionPolicy) {
        updateDraft { draft -> draft.copy(transcriptionPolicy = value) }
    }

    fun updateRecordingUploadPolicyOverride(value: UploadPolicy?) {
        editor.update { state -> state.copy(recordingUploadPolicyOverride = value) }
    }

    fun updateRecordingTranscriptionPolicyOverride(value: TranscriptionPolicy?) {
        editor.update { state -> state.copy(recordingTranscriptionPolicyOverride = value) }
    }

    fun updateOfflineLibrarySearchQuery(value: String) {
        editor.update { state ->
            state.copy(offlineLibrarySearchQuery = value.take(MAX_OFFLINE_LIBRARY_SEARCH_LENGTH))
        }
    }

    fun clearOfflineLibrarySearch() {
        editor.update { state -> state.copy(offlineLibrarySearchQuery = "") }
    }

    fun startAssetMetadataEdit(value: String) {
        val currentState = uiState.value
        val serverProfileId =
            currentState.activeServerProfileId
                ?.let { id -> runCatching { ServerProfileId.parse(id) }.getOrNull() }
                ?: return
        val asset =
            currentState.syncedAssets.firstOrNull { candidate -> candidate.id == value && !candidate.isTrashed }
                ?: return
        beginAssetMetadataLoad(
            serverProfileId = serverProfileId,
            assetId = asset.id,
            title = asset.title,
            language = asset.language,
            collectionId = asset.collectionId.orEmpty(),
            version = asset.version,
        )
    }

    fun updateAssetMetadataTitle(value: String) {
        updateAssetMetadataDraft { edit -> edit.copy(title = value.take(MAX_ASSET_TITLE_LENGTH)) }
    }

    fun updateAssetMetadataLanguage(value: String) {
        updateAssetMetadataDraft { edit -> edit.copy(language = value.take(MAX_ASSET_LANGUAGE_LENGTH)) }
    }

    fun updateAssetMetadataCollectionId(value: String) {
        updateAssetMetadataDraft { edit -> edit.copy(collectionId = value.take(MAX_ASSET_COLLECTION_ID_LENGTH)) }
    }

    fun saveAssetMetadata() {
        val editorState = editor.value
        val current = editorState.assetMetadataEdit ?: return
        val session = current.session ?: return
        if (current.status != AssetMetadataEditorStatus.EDITING) {
            return
        }
        val savingState =
            editorState.copy(
                assetMetadataEdit = current.copy(status = AssetMetadataEditorStatus.SAVING, error = null),
            )
        if (!editor.compareAndSet(editorState, savingState)) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val updated =
                    assetMetadataEditor.save(
                        session = session,
                        title = current.title,
                        language = current.language,
                        collectionId = current.collectionId.trim().takeIf(String::isNotEmpty),
                    )
                if (updated.serverProfileId != current.serverProfileId || updated.assetId != current.assetId) {
                    throw VoiceAssetProtocolException("Asset metadata cache refresh returned a different resource.")
                }
                editor.update { state ->
                    if (state.assetMetadataEdit?.requestId == current.requestId) {
                        state.copy(
                            assetMetadataEdit =
                                current.copy(
                                    status = AssetMetadataEditorStatus.SAVED,
                                    title = updated.title,
                                    language = updated.language,
                                    collectionId = updated.collectionId.orEmpty(),
                                    version = updated.version,
                                    error = null,
                                    session = null,
                                ),
                        )
                    } else {
                        state
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val error = assetMetadataError(exception, loading = false)
                editor.update { state ->
                    if (state.assetMetadataEdit?.requestId == current.requestId) {
                        state.copy(
                            assetMetadataEdit =
                                current.copy(
                                    status =
                                        if (error == AssetMetadataEditorError.INVALID_INPUT) {
                                            AssetMetadataEditorStatus.EDITING
                                        } else {
                                            AssetMetadataEditorStatus.FAILED
                                        },
                                    error = error,
                                    session = session.takeIf { error == AssetMetadataEditorError.INVALID_INPUT },
                                ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun reloadAssetMetadata() {
        val current = editor.value.assetMetadataEdit ?: return
        if (current.status != AssetMetadataEditorStatus.FAILED) {
            return
        }
        beginAssetMetadataLoad(
            serverProfileId = current.serverProfileId,
            assetId = current.assetId,
            title = current.title,
            language = current.language,
            collectionId = current.collectionId,
            version = current.version,
        )
    }

    fun closeAssetMetadataEditor() {
        editor.update { state -> state.copy(assetMetadataEdit = null) }
    }

    private fun beginAssetMetadataLoad(
        serverProfileId: ServerProfileId,
        assetId: String,
        title: String,
        language: String,
        collectionId: String,
        version: Long?,
    ) {
        val requestId = assetMetadataRequestIds.incrementAndGet()
        val loading =
            AssetMetadataEditInternalState(
                requestId = requestId,
                serverProfileId = serverProfileId,
                assetId = assetId,
                status = AssetMetadataEditorStatus.LOADING,
                title = title,
                language = language,
                collectionId = collectionId,
                version = version,
            )
        editor.update { state -> state.copy(assetMetadataEdit = loading) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val session = assetMetadataEditor.load(serverProfileId, assetId)
                if (session.serverProfileId != serverProfileId || session.assetId != assetId) {
                    throw VoiceAssetProtocolException("Asset metadata editor loaded a different resource.")
                }
                editor.update { state ->
                    if (state.assetMetadataEdit?.requestId == requestId) {
                        state.copy(
                            assetMetadataEdit =
                                loading.copy(
                                    status = AssetMetadataEditorStatus.EDITING,
                                    title = session.title,
                                    language = session.language,
                                    collectionId = session.collectionId.orEmpty(),
                                    version = session.version,
                                    error = null,
                                    session = session,
                                ),
                        )
                    } else {
                        state
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                editor.update { state ->
                    if (state.assetMetadataEdit?.requestId == requestId) {
                        state.copy(
                            assetMetadataEdit =
                                loading.copy(
                                    status = AssetMetadataEditorStatus.FAILED,
                                    error = assetMetadataError(exception, loading = true),
                                ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun updateAssetMetadataDraft(transform: (AssetMetadataEditInternalState) -> AssetMetadataEditInternalState) {
        editor.update { state ->
            val current = state.assetMetadataEdit
            if (current?.status == AssetMetadataEditorStatus.EDITING) {
                state.copy(assetMetadataEdit = transform(current).copy(error = null))
            } else {
                state
            }
        }
    }

    fun selectServerProfile(value: String) {
        val profileId = runCatching { ServerProfileId.parse(value) }.getOrNull() ?: return
        val currentState = uiState.value
        if (
            currentState.activeServerProfileId == profileId.value ||
            !currentState.recordingStatus.allowsServerSwitch()
        ) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            if (profiles.find(profileId) != null) {
                activeProfile.set(profileId)
                editor.update { state -> state.copy(assetMetadataEdit = null) }
                refreshRemoteAssetsIfAuthenticated(profileId)
            }
        }
    }

    fun refreshRemoteAssets() {
        val profileId = uiState.value.activeServerProfileId?.let(ServerProfileId::parse) ?: return
        viewModelScope.launch(ioDispatcher) {
            refreshRemoteAssetsIfAuthenticated(profileId)
        }
    }

    private suspend fun refreshRemoteAssetsIfAuthenticated(profileId: ServerProfileId) {
        if (!StartupSyncPolicy(credentials).hasReadableSession(profileId)) {
            return
        }
        runCatching { remoteAssetSyncScheduler.refresh(profileId) }
    }

    fun reconnectActiveServerProfile() {
        val serverProfileId =
            uiState.value.activeServerProfileId
                ?.let { value -> runCatching { ServerProfileId.parse(value) }.getOrNull() }
                ?: return
        val current = editor.value.sessionReconnect
        if (
            current.serverProfileId != serverProfileId ||
            current.status == ServerSessionReconnectStatus.SUBMITTING
        ) {
            return
        }
        val email = current.email.trim()
        val password = current.password.value
        val validationError =
            when {
                !EMAIL.matches(email) || email.length > MAX_EMAIL_LENGTH || email.any(Char::isISOControl) ->
                    ServerSessionReconnectError.INVALID_EMAIL
                password.isEmpty() || password.length > MAX_PASSWORD_LENGTH ->
                    ServerSessionReconnectError.INVALID_PASSWORD
                else -> null
            }
        val requestId = sessionReconnectRequestIds.incrementAndGet()
        val submitting =
            current.copy(
                requestId = requestId,
                status = ServerSessionReconnectStatus.SUBMITTING,
                email = email,
                password = SecretInput.EMPTY,
                error = null,
            )
        editor.update { state ->
            if (state.sessionReconnect == current) {
                state.copy(sessionReconnect = submitting)
            } else {
                state
            }
        }
        if (editor.value.sessionReconnect != submitting) {
            return
        }
        if (validationError != null) {
            editor.update { state ->
                if (state.sessionReconnect.requestId == requestId) {
                    state.copy(
                        sessionReconnect =
                            submitting.copy(
                                status = ServerSessionReconnectStatus.FAILED,
                                error = validationError,
                            ),
                    )
                } else {
                    state
                }
            }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                profileReconnector.reconnect(serverProfileId, email, password)
                editor.update { state ->
                    if (state.sessionReconnect.requestId == requestId) {
                        state.copy(
                            sessionReconnect =
                                ServerSessionReconnectInternalState(
                                    requestId = requestId,
                                    serverProfileId = serverProfileId,
                                    status = ServerSessionReconnectStatus.SUCCEEDED,
                                ),
                            deviceSessions = DeviceSessionsInternalState(),
                        )
                    } else {
                        state
                    }
                }
                runCatching { remoteAssetSyncScheduler.refresh(serverProfileId) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                editor.update { state ->
                    if (state.sessionReconnect.requestId == requestId) {
                        state.copy(
                            sessionReconnect =
                                submitting.copy(
                                    status = ServerSessionReconnectStatus.FAILED,
                                    error = sessionReconnectError(exception),
                                ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun refreshDeviceSessions() {
        val serverProfileId =
            uiState.value.activeServerProfileId
                ?.let { value -> runCatching { ServerProfileId.parse(value) }.getOrNull() }
                ?: return
        val current = editor.value.deviceSessions
        if (current.pendingRevocationId != null || current.revokingId != null) {
            return
        }
        val requestId = deviceSessionRequestIds.incrementAndGet()
        val loading =
            DeviceSessionsInternalState(
                requestId = requestId,
                serverProfileId = serverProfileId,
                status = DeviceSessionsStatus.LOADING,
            )
        editor.update { state ->
            if (state.deviceSessions == current) {
                state.copy(deviceSessions = loading)
            } else {
                state
            }
        }
        if (editor.value.deviceSessions != loading) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val snapshot = personalDeviceSessions.load(serverProfileId)
                if (snapshot.serverProfileId != serverProfileId) {
                    throw VoiceAssetProtocolException("Device sessions returned a different server profile.")
                }
                editor.update { state ->
                    if (state.deviceSessions.requestId == requestId) {
                        state.copy(
                            deviceSessions =
                                loading.copy(
                                    status = DeviceSessionsStatus.READY,
                                    snapshot = snapshot,
                                ),
                        )
                    } else {
                        state
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                editor.update { state ->
                    if (state.deviceSessions.requestId == requestId) {
                        state.copy(
                            deviceSessions =
                                loading.copy(
                                    status = DeviceSessionsStatus.FAILED,
                                    error = deviceSessionsError(exception, revoking = false),
                                ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun requestDeviceSessionRevocation(deviceSessionId: String) {
        val current = editor.value.deviceSessions
        val snapshot = current.snapshot ?: return
        if (
            current.status != DeviceSessionsStatus.READY ||
            current.pendingRevocationId != null ||
            current.revokingId != null ||
            snapshot.items.none { item -> item.id == deviceSessionId }
        ) {
            return
        }
        editor.update { state ->
            if (state.deviceSessions == current) {
                state.copy(
                    deviceSessions =
                        current.copy(
                            pendingRevocationId = deviceSessionId,
                            error = null,
                        ),
                )
            } else {
                state
            }
        }
    }

    fun cancelDeviceSessionRevocation() {
        editor.update { state ->
            val current = state.deviceSessions
            if (current.pendingRevocationId != null && current.revokingId == null) {
                state.copy(deviceSessions = current.copy(pendingRevocationId = null))
            } else {
                state
            }
        }
    }

    fun confirmDeviceSessionRevocation() {
        val current = editor.value.deviceSessions
        val snapshot = current.snapshot ?: return
        val targetId = current.pendingRevocationId ?: return
        if (current.status != DeviceSessionsStatus.READY || current.revokingId != null) {
            return
        }
        if (snapshot.items.none { item -> item.id == targetId }) {
            editor.update { state ->
                if (state.deviceSessions == current) {
                    state.copy(
                        deviceSessions =
                            current.copy(
                                pendingRevocationId = null,
                                error = DeviceSessionsError.NOT_FOUND,
                            ),
                    )
                } else {
                    state
                }
            }
            return
        }
        val requestId = deviceSessionRequestIds.incrementAndGet()
        val revoking =
            current.copy(
                requestId = requestId,
                pendingRevocationId = null,
                revokingId = targetId,
                error = null,
            )
        editor.update { state ->
            if (state.deviceSessions == current) {
                state.copy(deviceSessions = revoking)
            } else {
                state
            }
        }
        if (editor.value.deviceSessions != revoking) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val revoked = personalDeviceSessions.revoke(snapshot.serverProfileId, targetId)
                if (revoked.id != targetId) {
                    throw VoiceAssetProtocolException("Device session revocation returned a different session.")
                }
                editor.update { state ->
                    if (state.deviceSessions.requestId != requestId) {
                        state
                    } else if (revoked.current) {
                        state.copy(
                            deviceSessions =
                                DeviceSessionsInternalState(
                                    requestId = requestId,
                                    serverProfileId = snapshot.serverProfileId,
                                    status = DeviceSessionsStatus.FAILED,
                                    error = DeviceSessionsError.AUTHENTICATION_REQUIRED,
                                ),
                        )
                    } else {
                        state.copy(
                            deviceSessions =
                                revoking.copy(
                                    status = DeviceSessionsStatus.READY,
                                    snapshot =
                                        snapshot.copy(
                                            items = snapshot.items.filterNot { item -> item.id == targetId },
                                        ),
                                    revokingId = null,
                                ),
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                editor.update { state ->
                    if (state.deviceSessions.requestId == requestId) {
                        state.copy(
                            deviceSessions =
                                revoking.copy(
                                    status = DeviceSessionsStatus.FAILED,
                                    revokingId = null,
                                    error = deviceSessionsError(exception, revoking = true),
                                ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun refreshMobileAdministration() {
        val serverProfileId =
            uiState.value.activeServerProfileId
                ?.let { value -> runCatching { ServerProfileId.parse(value) }.getOrNull() }
                ?: return
        val requestId = mobileAdministrationRequestIds.incrementAndGet()
        val loading =
            MobileAdministrationInternalState(
                requestId = requestId,
                serverProfileId = serverProfileId,
                status = MobileAdministrationStatus.LOADING,
            )
        editor.update { state ->
            if (
                state.mobileAdministration.busyProviderProfileId == null &&
                state.mobileAdministration.busyJobId == null
            ) {
                state.copy(mobileAdministration = loading)
            } else {
                state
            }
        }
        if (editor.value.mobileAdministration != loading) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val snapshot = mobileAdministration.load(serverProfileId)
                if (snapshot.serverProfileId != serverProfileId) {
                    throw VoiceAssetProtocolException("Mobile administration returned a different server profile.")
                }
                editor.update { state ->
                    if (state.mobileAdministration.requestId == requestId) {
                        state.copy(
                            mobileAdministration =
                                loading.copy(
                                    status = MobileAdministrationStatus.READY,
                                    snapshot = snapshot,
                                ),
                        )
                    } else {
                        state
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                editor.update { state ->
                    if (state.mobileAdministration.requestId == requestId) {
                        state.copy(
                            mobileAdministration =
                                loading.copy(
                                    status = MobileAdministrationStatus.FAILED,
                                    error = mobileAdministrationError(exception, updating = false),
                                ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun setMobileProviderProfileEnabled(
        value: String,
        enabled: Boolean,
    ) {
        val current = editor.value.mobileAdministration
        val snapshot = current.snapshot ?: return
        if (
            current.status != MobileAdministrationStatus.READY ||
            current.busyProviderProfileId != null ||
            current.busyJobId != null
        ) {
            return
        }
        val selected = snapshot.providers.firstOrNull { provider -> provider.profile.id == value } ?: return
        val targetState = if (enabled) ProviderProfileState.ENABLED else ProviderProfileState.DISABLED
        if (selected.profile.state == targetState) {
            return
        }
        val requestId = mobileAdministrationRequestIds.incrementAndGet()
        val updating =
            current.copy(
                requestId = requestId,
                busyProviderProfileId = selected.profile.id,
                busyProviderAction = MobileProviderAction.STATE_UPDATE,
                error = null,
            )
        editor.update { state ->
            if (state.mobileAdministration == current) {
                state.copy(mobileAdministration = updating)
            } else {
                state
            }
        }
        if (editor.value.mobileAdministration != updating) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val updated =
                    mobileAdministration.setProviderProfileState(
                        serverProfileId = snapshot.serverProfileId,
                        family = selected.family,
                        providerProfileId = selected.profile.id,
                        expectedVersion = selected.profile.version,
                        state = targetState,
                    )
                validateUpdatedProviderProfile(selected, updated, targetState)
                val updatedSnapshot = snapshot.withUpdatedProvider(selected, updated)
                editor.update { state ->
                    if (state.mobileAdministration.requestId == requestId) {
                        state.copy(
                            mobileAdministration =
                                updating.copy(
                                    snapshot = updatedSnapshot,
                                    busyProviderProfileId = null,
                                    busyProviderAction = null,
                                ),
                        )
                    } else {
                        state
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                editor.update { state ->
                    if (state.mobileAdministration.requestId == requestId) {
                        state.copy(
                            mobileAdministration =
                                updating.copy(
                                    busyProviderProfileId = null,
                                    busyProviderAction = null,
                                    error = mobileAdministrationError(exception, updating = true),
                                ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun checkMobileProviderProfileHealth(value: String) {
        val current = editor.value.mobileAdministration
        val snapshot = current.snapshot ?: return
        if (
            current.status != MobileAdministrationStatus.READY ||
            current.busyProviderProfileId != null ||
            current.busyJobId != null
        ) {
            return
        }
        val selected = snapshot.providers.firstOrNull { provider -> provider.profile.id == value } ?: return
        val requestId = mobileAdministrationRequestIds.incrementAndGet()
        val checking =
            current.copy(
                requestId = requestId,
                busyProviderProfileId = selected.profile.id,
                busyProviderAction = MobileProviderAction.HEALTH_CHECK,
                error = null,
            )
        editor.update { state ->
            if (state.mobileAdministration == current) {
                state.copy(mobileAdministration = checking)
            } else {
                state
            }
        }
        if (editor.value.mobileAdministration != checking) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val health =
                    mobileAdministration.checkProviderProfileHealth(
                        serverProfileId = snapshot.serverProfileId,
                        family = selected.family,
                        providerProfileId = selected.profile.id,
                    )
                if (health.profileId != selected.profile.id) {
                    throw VoiceAssetProtocolException(
                        "Mobile administration returned health for a different provider profile.",
                    )
                }
                val updatedSnapshot = snapshot.withProviderHealth(selected, health)
                editor.update { state ->
                    if (state.mobileAdministration.requestId == requestId) {
                        state.copy(
                            mobileAdministration =
                                checking.copy(
                                    snapshot = updatedSnapshot,
                                    busyProviderProfileId = null,
                                    busyProviderAction = null,
                                ),
                        )
                    } else {
                        state
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                editor.update { state ->
                    if (state.mobileAdministration.requestId == requestId) {
                        state.copy(
                            mobileAdministration =
                                checking.copy(
                                    busyProviderProfileId = null,
                                    busyProviderAction = null,
                                    error = mobileAdministrationHealthError(exception),
                                ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun retryMobileAdministrationJob(value: String) {
        val current = editor.value.mobileAdministration
        val snapshot = current.snapshot ?: return
        if (
            current.status != MobileAdministrationStatus.READY ||
            current.busyProviderProfileId != null ||
            current.busyJobId != null
        ) {
            return
        }
        val selected = snapshot.jobs.firstOrNull { job -> job.id == value && job.retryable } ?: return
        val requestId = mobileAdministrationRequestIds.incrementAndGet()
        val retrying =
            current.copy(
                requestId = requestId,
                busyJobId = selected.id,
                error = null,
            )
        editor.update { state ->
            if (state.mobileAdministration == current) {
                state.copy(mobileAdministration = retrying)
            } else {
                state
            }
        }
        if (editor.value.mobileAdministration != retrying) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            try {
                val retried =
                    mobileAdministration.retryJob(
                        serverProfileId = snapshot.serverProfileId,
                        jobId = selected.id,
                    )
                validateRetriedAdministrationJob(selected, retried)
                val updatedSnapshot = snapshot.withRetriedJob(selected, retried)
                editor.update { state ->
                    if (state.mobileAdministration.requestId == requestId) {
                        state.copy(
                            mobileAdministration =
                                retrying.copy(
                                    snapshot = updatedSnapshot,
                                    busyJobId = null,
                                ),
                        )
                    } else {
                        state
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                editor.update { state ->
                    if (state.mobileAdministration.requestId == requestId) {
                        state.copy(
                            mobileAdministration =
                                retrying.copy(
                                    busyJobId = null,
                                    error = mobileAdministrationJobRetryError(exception),
                                ),
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun retryRecordingSync(value: String) {
        val recordingSessionId = runCatching { RecordingSessionId.parse(value) }.getOrNull() ?: return
        val visible = uiState.value.localRecordings.firstOrNull { it.id == recordingSessionId.value } ?: return
        if (visible.syncStatus !in setOf(SyncUiStatus.BLOCKED, SyncUiStatus.FAILED)) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            var prepared: SyncTask? = null
            try {
                val recording = recordings.find(recordingSessionId) ?: return@launch
                val profileId = recording.session.serverProfileId ?: return@launch
                if (recording.status != StoredRecordingStatus.SAVED || recording.recording == null) {
                    return@launch
                }
                val profile = profiles.find(profileId) ?: return@launch
                if (!StartupSyncPolicy(credentials).hasReadableSession(profileId)) {
                    return@launch
                }
                val task = syncTasks.find(recordingSessionId) ?: return@launch
                val retryTranscription =
                    recording.session.effectiveTranscriptionPolicy(profile) == TranscriptionPolicy.MANUAL &&
                        (task.stage == SyncStage.TRANSCRIPTION_REQUESTED || task.transcriptionJobId != null)
                prepared =
                    when {
                        task.stage == SyncStage.FAILED ->
                            syncTasks.transition(
                                recordingSessionId,
                                SyncEvent.ManualRetry,
                                clock(),
                            )
                        task.blockReason != SyncBlockReason.NONE -> task
                        else -> return@launch
                    }
                val enqueued =
                    if (retryTranscription) {
                        syncScheduler.enqueueTranscription(recording.session, profile, force = true)
                    } else {
                        syncScheduler.enqueue(recording.session, profile, force = true)
                    }
                check(enqueued) {
                    "forced sync retry was not enqueued"
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                val checkpoint = prepared
                if (
                    checkpoint != null &&
                    checkpoint.stage != SyncStage.FAILED &&
                    checkpoint.blockReason == SyncBlockReason.NONE
                ) {
                    runCatching {
                        syncTasks.transition(
                            recordingSessionId,
                            SyncEvent.PermanentFailure("retry_schedule_failed"),
                            clock(),
                        )
                    }
                }
            }
        }
    }

    fun startRecordingUpload(value: String) {
        val recordingSessionId = runCatching { RecordingSessionId.parse(value) }.getOrNull() ?: return
        val visible = uiState.value.localRecordings.firstOrNull { it.id == recordingSessionId.value } ?: return
        if (!visible.canStartUpload) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val recording = recordings.find(recordingSessionId) ?: return@launch
            val profileId = recording.session.serverProfileId ?: return@launch
            if (
                recording.status != StoredRecordingStatus.SAVED ||
                recording.recording == null ||
                uiState.value.activeServerProfileId != profileId.value
            ) {
                return@launch
            }
            val profile = profiles.find(profileId) ?: return@launch
            if (!StartupSyncPolicy(credentials).hasReadableSession(profileId)) {
                return@launch
            }
            if (recording.session.effectiveUploadPolicy(profile) == UploadPolicy.MANUAL) {
                try {
                    check(syncScheduler.enqueue(recording.session, profile, force = true)) {
                        "manual upload was not enqueued"
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    markScheduleFailure(recording, "upload_schedule_failed")
                }
            }
        }
    }

    fun startRecordingTranscription(value: String) {
        val recordingSessionId = runCatching { RecordingSessionId.parse(value) }.getOrNull() ?: return
        val visible = uiState.value.localRecordings.firstOrNull { it.id == recordingSessionId.value } ?: return
        if (!visible.canStartTranscription) {
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val recording = recordings.find(recordingSessionId) ?: return@launch
            val profileId = recording.session.serverProfileId ?: return@launch
            if (
                recording.status != StoredRecordingStatus.SAVED ||
                recording.recording == null ||
                uiState.value.activeServerProfileId != profileId.value
            ) {
                return@launch
            }
            val profile = profiles.find(profileId) ?: return@launch
            if (!StartupSyncPolicy(credentials).hasReadableSession(profileId)) {
                return@launch
            }
            val task = syncTasks.find(recordingSessionId) ?: return@launch
            if (
                recording.session.effectiveTranscriptionPolicy(profile) == TranscriptionPolicy.MANUAL &&
                task.stage == SyncStage.UPLOAD_COMPLETED &&
                task.blockReason == SyncBlockReason.NONE
            ) {
                try {
                    check(syncScheduler.enqueueTranscription(recording.session, profile, force = true)) {
                        "manual transcription was not enqueued"
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    markScheduleFailure(recording, "transcription_schedule_failed")
                }
            }
        }
    }

    private suspend fun markScheduleFailure(
        recording: StoredRecording,
        errorCode: String,
    ) {
        val localRecording = recording.recording ?: return
        val profileId = recording.session.serverProfileId ?: return
        val existing =
            syncTasks.find(recording.session.id)
                ?: syncTasks.create(
                    SyncTask.create(
                        recordingSessionId = recording.session.id,
                        serverProfileId = profileId,
                        totalBytes = localRecording.sizeBytes,
                        createdAtEpochMillis = maxOf(clock(), recording.updatedAtEpochMillis),
                    ),
                )
        if (
            existing.stage !in setOf(SyncStage.COMPLETE, SyncStage.FAILED) &&
            existing.blockReason == SyncBlockReason.NONE
        ) {
            syncTasks.transition(
                recording.session.id,
                SyncEvent.PermanentFailure(errorCode),
                maxOf(clock(), existing.updatedAtEpochMillis),
            )
        }
    }

    fun saveServerProfile() {
        if (editor.value.isSaving) {
            return
        }
        val draft = editor.value.draft
        val validated = validate(draft) ?: return

        editor.update { state ->
            state.copy(
                draft = state.draft.copy(password = SecretInput.EMPTY),
                isSaving = true,
                error = null,
            )
        }
        viewModelScope.launch(ioDispatcher) {
            var credentialWritten = false
            var profileSaved = false
            try {
                val login =
                    authenticator.authenticate(
                        profile = validated.profile,
                        email = validated.email,
                        password = validated.password,
                    )
                credentials.writeSession(
                    validated.profile.id,
                    StoredServerSession.fromLogin(login),
                )
                credentialWritten = true
                profiles.save(validated.profile)
                profileSaved = true
                activeProfile.set(validated.profile.id)
                runCatching { remoteAssetSyncScheduler.schedule(validated.profile.id) }
                editor.update { state ->
                    ServerProfileEditorState(
                        recordingUploadPolicyOverride = state.recordingUploadPolicyOverride,
                        recordingTranscriptionPolicyOverride = state.recordingTranscriptionPolicyOverride,
                        offlineLibrarySearchQuery = state.offlineLibrarySearchQuery,
                    )
                }
            } catch (exception: CancellationException) {
                if (credentialWritten && !profileSaved) {
                    runCatching { credentials.remove(validated.profile.id) }
                }
                throw exception
            } catch (exception: Exception) {
                if (credentialWritten && !profileSaved) {
                    runCatching { credentials.remove(validated.profile.id) }
                }
                editor.update { state ->
                    state.copy(isSaving = false, error = formError(exception))
                }
            }
        }
    }

    fun pairServerProfile() {
        val current = editor.value
        if (current.isSaving) {
            return
        }
        val payload = current.draft.pairingPayload.value
        if (payload.isBlank() || payload.length > MAX_PAIRING_PAYLOAD_LENGTH) {
            setError(ServerProfileFormError.INVALID_PAIRING)
            return
        }
        val uploadPolicy = current.draft.uploadPolicy
        val transcriptionPolicy = current.draft.transcriptionPolicy
        editor.update { state ->
            state.copy(
                draft =
                    state.draft.copy(
                        password = SecretInput.EMPTY,
                        pairingPayload = SecretInput.EMPTY,
                    ),
                isSaving = true,
                error = null,
            )
        }
        viewModelScope.launch(ioDispatcher) {
            var credentialWritten = false
            var profileSaved = false
            var profileId: ServerProfileId? = null
            try {
                val paired = pairingAuthenticator.authenticate(payload, DEFAULT_DEVICE_NAME)
                val timestamp = clock()
                val origin = ServerOrigin.parse(paired.origin)
                val generatedProfileId = idFactory()
                profileId = generatedProfileId
                val profile =
                    ServerProfile.create(
                        id = generatedProfileId,
                        name =
                            requireNotNull(URI(origin.value).host)
                                .take(MAX_PAIRED_PROFILE_NAME_LENGTH),
                        baseUrl = origin.value,
                        authenticationMode = AuthenticationMode.LOCAL_SESSION,
                        defaultUploadPolicy = uploadPolicy,
                        defaultTranscriptionPolicy = transcriptionPolicy,
                        customCaPem = null,
                        certificateFingerprint = null,
                        createdAtEpochMillis = timestamp,
                        updatedAtEpochMillis = timestamp,
                    )
                credentials.writeSession(
                    profile.id,
                    StoredServerSession.fromLogin(paired.login),
                )
                credentialWritten = true
                profiles.save(profile)
                profileSaved = true
                activeProfile.set(profile.id)
                runCatching { remoteAssetSyncScheduler.schedule(profile.id) }
                editor.update { state ->
                    ServerProfileEditorState(
                        recordingUploadPolicyOverride = state.recordingUploadPolicyOverride,
                        recordingTranscriptionPolicyOverride = state.recordingTranscriptionPolicyOverride,
                        offlineLibrarySearchQuery = state.offlineLibrarySearchQuery,
                    )
                }
            } catch (exception: CancellationException) {
                if (credentialWritten && !profileSaved && profileId != null) {
                    runCatching { credentials.remove(profileId) }
                }
                throw exception
            } catch (exception: Exception) {
                if (credentialWritten && !profileSaved && profileId != null) {
                    runCatching { credentials.remove(profileId) }
                }
                editor.update { state ->
                    state.copy(isSaving = false, error = pairingFormError(exception))
                }
            }
        }
    }

    private fun validate(draft: ServerProfileDraft): ValidatedProfile? {
        if (draft.name.isBlank()) {
            setError(ServerProfileFormError.INVALID_NAME)
            return null
        }
        if (runCatching { ServerOrigin.parse(draft.baseUrl) }.isFailure) {
            setError(ServerProfileFormError.INVALID_URL)
            return null
        }
        val email = draft.email.trim()
        if (!EMAIL.matches(email) || email.length > 254 || email.any(Char::isISOControl)) {
            setError(ServerProfileFormError.INVALID_EMAIL)
            return null
        }
        val password = draft.password.value
        if (password.isEmpty() || password.length > MAX_PASSWORD_LENGTH) {
            setError(ServerProfileFormError.INVALID_PASSWORD)
            return null
        }
        val customCaPem = draft.customCaPem.trim().takeIf(String::isNotEmpty)
        if (
            customCaPem != null &&
            (
                customCaPem.length > MAX_CUSTOM_CA_LENGTH ||
                    !customCaPem.startsWith("-----BEGIN CERTIFICATE-----") ||
                    !customCaPem.endsWith("-----END CERTIFICATE-----")
            )
        ) {
            setError(ServerProfileFormError.INVALID_CUSTOM_CA)
            return null
        }
        val fingerprint =
            if (draft.certificateFingerprint.isBlank()) {
                null
            } else {
                runCatching { CertificateFingerprint.parse(draft.certificateFingerprint) }
                    .getOrElse {
                        setError(ServerProfileFormError.INVALID_FINGERPRINT)
                        return null
                    }
            }
        val timestamp = clock()
        val profile =
            runCatching {
                ServerProfile.create(
                    id = idFactory(),
                    name = draft.name,
                    baseUrl = draft.baseUrl,
                    authenticationMode = AuthenticationMode.LOCAL_SESSION,
                    defaultUploadPolicy = draft.uploadPolicy,
                    defaultTranscriptionPolicy = draft.transcriptionPolicy,
                    customCaPem = customCaPem,
                    certificateFingerprint = fingerprint,
                    createdAtEpochMillis = timestamp,
                    updatedAtEpochMillis = timestamp,
                )
            }
        if (profile.isFailure) {
            setError(ServerProfileFormError.INVALID_NAME)
        }
        return profile.getOrNull()?.let { ValidatedProfile(it, email, password) }
    }

    private fun formError(exception: Exception): ServerProfileFormError =
        when (exception) {
            is VoiceAssetApiException ->
                if (exception.statusCode == 401 || exception.statusCode == 403) {
                    ServerProfileFormError.AUTHENTICATION_FAILED
                } else {
                    ServerProfileFormError.SAVE_FAILED
                }
            is VoiceAssetTlsException -> ServerProfileFormError.TLS_FAILED
            is VoiceAssetConnectionException -> ServerProfileFormError.CONNECTION_FAILED
            is VoiceAssetProtocolException -> ServerProfileFormError.INCOMPATIBLE_SERVER
            is IllegalArgumentException -> ServerProfileFormError.INVALID_CUSTOM_CA
            is CredentialStoreException -> ServerProfileFormError.SECURE_STORAGE_FAILED
            else -> ServerProfileFormError.SAVE_FAILED
        }

    private fun pairingFormError(exception: Exception): ServerProfileFormError =
        when (exception) {
            is InvalidPairingPayloadException -> ServerProfileFormError.INVALID_PAIRING
            is VoiceAssetApiException ->
                if (exception.statusCode == 401 || exception.statusCode == 403) {
                    ServerProfileFormError.INVALID_PAIRING
                } else {
                    ServerProfileFormError.SAVE_FAILED
                }
            is VoiceAssetTlsException -> ServerProfileFormError.TLS_FAILED
            is VoiceAssetConnectionException -> ServerProfileFormError.CONNECTION_FAILED
            is VoiceAssetProtocolException -> ServerProfileFormError.INCOMPATIBLE_SERVER
            is CredentialStoreException -> ServerProfileFormError.SECURE_STORAGE_FAILED
            is IllegalArgumentException -> ServerProfileFormError.INVALID_PAIRING
            else -> ServerProfileFormError.SAVE_FAILED
        }

    private fun sessionReconnectError(exception: Exception): ServerSessionReconnectError =
        when (exception) {
            is ServerProfileReconnectProfileUnavailableException ->
                ServerSessionReconnectError.PROFILE_UNAVAILABLE
            is VoiceAssetApiException ->
                if (exception.statusCode == 401 || exception.statusCode == 403) {
                    ServerSessionReconnectError.AUTHENTICATION_FAILED
                } else {
                    ServerSessionReconnectError.RECONNECT_FAILED
                }
            is VoiceAssetTlsException -> ServerSessionReconnectError.TLS_FAILED
            is VoiceAssetConnectionException -> ServerSessionReconnectError.CONNECTION_FAILED
            is VoiceAssetProtocolException -> ServerSessionReconnectError.PROTOCOL_MISMATCH
            is CredentialStoreException -> ServerSessionReconnectError.SECURE_STORAGE_FAILED
            is IllegalArgumentException -> ServerSessionReconnectError.RECONNECT_FAILED
            else -> ServerSessionReconnectError.RECONNECT_FAILED
        }

    private fun deviceSessionsError(
        exception: Exception,
        revoking: Boolean,
    ): DeviceSessionsError =
        when (exception) {
            is PersonalDeviceSessionsAuthenticationRequiredException ->
                DeviceSessionsError.AUTHENTICATION_REQUIRED
            is PersonalDeviceSessionsProfileUnavailableException ->
                DeviceSessionsError.PROFILE_UNAVAILABLE
            is PersonalDeviceSessionNotFoundException -> DeviceSessionsError.NOT_FOUND
            is VoiceAssetApiException ->
                when (exception.statusCode) {
                    401 -> DeviceSessionsError.AUTHENTICATION_REQUIRED
                    403 -> DeviceSessionsError.PERMISSION_DENIED
                    404 -> DeviceSessionsError.NOT_FOUND
                    else ->
                        if (revoking) {
                            DeviceSessionsError.REVOKE_FAILED
                        } else {
                            DeviceSessionsError.LOAD_FAILED
                        }
                }
            is VoiceAssetTlsException -> DeviceSessionsError.TLS_FAILED
            is VoiceAssetConnectionException -> DeviceSessionsError.CONNECTION_FAILED
            is VoiceAssetProtocolException -> DeviceSessionsError.PROTOCOL_MISMATCH
            is CredentialStoreException -> DeviceSessionsError.SECURE_STORAGE_FAILED
            is PersonalDeviceSessionsUnavailableException ->
                if (revoking) {
                    DeviceSessionsError.REVOKE_FAILED
                } else {
                    DeviceSessionsError.LOAD_FAILED
                }
            else ->
                if (revoking) {
                    DeviceSessionsError.REVOKE_FAILED
                } else {
                    DeviceSessionsError.LOAD_FAILED
                }
        }

    private fun mobileAdministrationError(
        exception: Exception,
        updating: Boolean,
    ): MobileAdministrationError =
        when (exception) {
            is MobileAdministrationAuthenticationRequiredException ->
                MobileAdministrationError.AUTHENTICATION_REQUIRED
            is MobileAdministrationProfileUnavailableException ->
                MobileAdministrationError.PROFILE_UNAVAILABLE
            is VoiceAssetApiException ->
                when (exception.statusCode) {
                    401 -> MobileAdministrationError.AUTHENTICATION_REQUIRED
                    403 -> MobileAdministrationError.PERMISSION_DENIED
                    409, 428 -> MobileAdministrationError.CONFLICT
                    else ->
                        if (updating) {
                            MobileAdministrationError.UPDATE_FAILED
                        } else {
                            MobileAdministrationError.LOAD_FAILED
                        }
                }
            is VoiceAssetTlsException -> MobileAdministrationError.TLS_FAILED
            is VoiceAssetConnectionException -> MobileAdministrationError.CONNECTION_FAILED
            is VoiceAssetProtocolException -> MobileAdministrationError.PROTOCOL_MISMATCH
            is CredentialStoreException -> MobileAdministrationError.SECURE_STORAGE_FAILED
            is MobileAdministrationUnavailableException ->
                if (updating) {
                    MobileAdministrationError.UPDATE_FAILED
                } else {
                    MobileAdministrationError.LOAD_FAILED
                }
            else ->
                if (updating) {
                    MobileAdministrationError.UPDATE_FAILED
                } else {
                    MobileAdministrationError.LOAD_FAILED
                }
        }

    private fun mobileAdministrationHealthError(exception: Exception): MobileAdministrationError {
        val error = mobileAdministrationError(exception, updating = true)
        return if (error == MobileAdministrationError.UPDATE_FAILED) {
            MobileAdministrationError.HEALTH_CHECK_FAILED
        } else {
            error
        }
    }

    private fun mobileAdministrationJobRetryError(exception: Exception): MobileAdministrationError {
        if (exception is VoiceAssetApiException && exception.statusCode == 409) {
            return MobileAdministrationError.JOB_NOT_RETRYABLE
        }
        val error = mobileAdministrationError(exception, updating = true)
        return if (error == MobileAdministrationError.UPDATE_FAILED) {
            MobileAdministrationError.JOB_RETRY_FAILED
        } else {
            error
        }
    }

    private fun validateRetriedAdministrationJob(
        previous: AdministrationJob,
        retried: AdministrationJob,
    ) {
        if (
            retried.id != previous.id ||
            retried.assetId != previous.assetId ||
            retried.createdBy != previous.createdBy ||
            retried.kind != previous.kind ||
            retried.state != "queued" ||
            retried.attempts != previous.attempts ||
            retried.maxAttempts != previous.maxAttempts + 1 ||
            retried.retryable ||
            retried.leaseExpiresAt != null ||
            retried.lastErrorCode != null ||
            retried.resultRevisionId != null
        ) {
            throw VoiceAssetProtocolException("Mobile administration returned an inconsistent job retry.")
        }
    }

    private fun validateUpdatedProviderProfile(
        previous: MobileAdministrationProviderProfile,
        updated: MobileAdministrationProviderProfile,
        targetState: ProviderProfileState,
    ) {
        if (
            updated.family != previous.family ||
            updated.profile.id != previous.profile.id ||
            updated.profile.workspaceId != previous.profile.workspaceId ||
            updated.profile.providerId != previous.profile.providerId ||
            updated.profile.state != targetState ||
            updated.profile.version <= previous.profile.version
        ) {
            throw VoiceAssetProtocolException("Mobile administration returned an inconsistent provider profile.")
        }
    }

    private fun assetMetadataError(
        exception: Exception,
        loading: Boolean,
    ): AssetMetadataEditorError =
        when (exception) {
            is AssetMetadataAuthenticationRequiredException -> AssetMetadataEditorError.AUTHENTICATION_REQUIRED
            is AssetMetadataProfileUnavailableException,
            is CachedRemoteAssetMissingException,
            -> AssetMetadataEditorError.NOT_FOUND
            is VoiceAssetApiException ->
                when (exception.statusCode) {
                    400, 413 -> AssetMetadataEditorError.INVALID_INPUT
                    401 -> AssetMetadataEditorError.AUTHENTICATION_REQUIRED
                    403 -> AssetMetadataEditorError.PERMISSION_DENIED
                    404 -> AssetMetadataEditorError.NOT_FOUND
                    409, 428 -> AssetMetadataEditorError.CONFLICT
                    else ->
                        if (loading) {
                            AssetMetadataEditorError.LOAD_FAILED
                        } else {
                            AssetMetadataEditorError.SAVE_FAILED
                        }
                }
            is VoiceAssetTlsException -> AssetMetadataEditorError.TLS_FAILED
            is VoiceAssetConnectionException -> AssetMetadataEditorError.CONNECTION_FAILED
            is VoiceAssetProtocolException -> AssetMetadataEditorError.PROTOCOL_MISMATCH
            is CredentialStoreException -> AssetMetadataEditorError.SECURE_STORAGE_FAILED
            is IllegalArgumentException -> AssetMetadataEditorError.INVALID_INPUT
            else ->
                if (loading) {
                    AssetMetadataEditorError.LOAD_FAILED
                } else {
                    AssetMetadataEditorError.SAVE_FAILED
                }
        }

    private fun updateDraft(transform: (ServerProfileDraft) -> ServerProfileDraft) {
        editor.update { state -> state.copy(draft = transform(state.draft), error = null) }
    }

    private fun updateSessionReconnect(transform: (ServerSessionReconnectInternalState) -> ServerSessionReconnectInternalState) {
        val serverProfileId =
            uiState.value.activeServerProfileId
                ?.let { value -> runCatching { ServerProfileId.parse(value) }.getOrNull() }
                ?: return
        editor.update { state ->
            val current =
                state.sessionReconnect.takeIf { reconnect -> reconnect.serverProfileId == serverProfileId }
                    ?: ServerSessionReconnectInternalState(serverProfileId = serverProfileId)
            if (current.status == ServerSessionReconnectStatus.SUBMITTING) {
                state
            } else {
                state.copy(
                    sessionReconnect =
                        transform(current).copy(
                            status = ServerSessionReconnectStatus.IDLE,
                            error = null,
                        ),
                )
            }
        }
    }

    private fun setError(error: ServerProfileFormError) {
        editor.update { state -> state.copy(error = error) }
    }

    private fun recordingStatus(recording: StoredRecording?): RecordingUiStatus =
        when (recording?.status) {
            null -> RecordingUiStatus.READY
            else -> recording.status.toUiStatus()
        }

    private data class ServerProfileEditorState(
        val draft: ServerProfileDraft = ServerProfileDraft(),
        val isSaving: Boolean = false,
        val error: ServerProfileFormError? = null,
        val recordingUploadPolicyOverride: UploadPolicy? = null,
        val recordingTranscriptionPolicyOverride: TranscriptionPolicy? = null,
        val offlineLibrarySearchQuery: String = "",
        val assetMetadataEdit: AssetMetadataEditInternalState? = null,
        val sessionReconnect: ServerSessionReconnectInternalState = ServerSessionReconnectInternalState(),
        val deviceSessions: DeviceSessionsInternalState = DeviceSessionsInternalState(),
        val mobileAdministration: MobileAdministrationInternalState = MobileAdministrationInternalState(),
    )

    private data class ServerSessionReconnectInternalState(
        val requestId: Long = 0,
        val serverProfileId: ServerProfileId? = null,
        val status: ServerSessionReconnectStatus = ServerSessionReconnectStatus.IDLE,
        val email: String = "",
        val password: SecretInput = SecretInput.EMPTY,
        val error: ServerSessionReconnectError? = null,
    ) {
        fun toUiState(activeProfileId: ServerProfileId?): ServerSessionReconnectUiState {
            if (serverProfileId == null || serverProfileId != activeProfileId) {
                return ServerSessionReconnectUiState()
            }
            return ServerSessionReconnectUiState(
                status = status,
                email = email,
                password = password,
                error = error,
            )
        }
    }

    private data class DeviceSessionsInternalState(
        val requestId: Long = 0,
        val serverProfileId: ServerProfileId? = null,
        val status: DeviceSessionsStatus = DeviceSessionsStatus.IDLE,
        val snapshot: PersonalDeviceSessionsSnapshot? = null,
        val pendingRevocationId: String? = null,
        val revokingId: String? = null,
        val error: DeviceSessionsError? = null,
    ) {
        fun toUiState(activeProfileId: ServerProfileId?): DeviceSessionsUiState {
            if (serverProfileId == null || serverProfileId != activeProfileId) {
                return DeviceSessionsUiState()
            }
            return DeviceSessionsUiState(
                status = status,
                items =
                    snapshot?.items.orEmpty().map { item ->
                        DeviceSessionSummary(
                            id = item.id,
                            deviceName = item.deviceName,
                            current = item.current,
                            lastSeenAt = item.lastSeenAt,
                            refreshExpiresAt = item.refreshExpiresAt,
                        )
                    },
                pendingRevocationId = pendingRevocationId,
                revokingId = revokingId,
                error = error,
            )
        }
    }

    private data class MobileAdministrationInternalState(
        val requestId: Long = 0,
        val serverProfileId: ServerProfileId? = null,
        val status: MobileAdministrationStatus = MobileAdministrationStatus.IDLE,
        val snapshot: MobileAdministrationSnapshot? = null,
        val busyJobId: String? = null,
        val busyProviderProfileId: String? = null,
        val busyProviderAction: MobileProviderAction? = null,
        val error: MobileAdministrationError? = null,
    ) {
        fun toUiState(activeProfileId: ServerProfileId?): MobileAdministrationUiState {
            if (serverProfileId == null || serverProfileId != activeProfileId) {
                return MobileAdministrationUiState()
            }
            val current = snapshot
            return MobileAdministrationUiState(
                status = status,
                systemStatus =
                    current?.systemStatus?.let { system ->
                        MobileSystemStatusSummary(
                            generatedAt = system.generatedAt,
                            activeUsers = system.activeUsers,
                            assetCount = system.assets.total,
                            storageObjectCount = system.storage.objectCount,
                            storageBytes = system.storage.bytes,
                            transcriptCount = system.transcripts.transcriptCount,
                            revisionCount = system.transcripts.revisionCount,
                            jobCount = system.jobs.total,
                            queuedJobCount = system.jobs.queued,
                            runningJobCount = system.jobs.running,
                            retryWaitJobCount = system.jobs.retryWait,
                            failedJobCount = system.jobs.failed,
                            enabledAsrCount = system.providers.enabledAsr,
                            enabledLlmCount = system.providers.enabledLlm,
                        )
                    },
                jobs =
                    current?.jobs.orEmpty().map { job ->
                        MobileAdministrationJobSummary(
                            id = job.id,
                            kind = job.kind,
                            state = job.state,
                            attempts = job.attempts,
                            maxAttempts = job.maxAttempts,
                            retryable = job.retryable,
                            lastErrorCode = job.lastErrorCode,
                            updatedAt = job.updatedAt,
                        )
                    },
                providers =
                    current?.providers.orEmpty().map { provider ->
                        MobileProviderProfileSummary(
                            id = provider.profile.id,
                            family = provider.family,
                            providerId = provider.profile.providerId,
                            displayName = provider.profile.displayName,
                            state = provider.profile.state,
                            priority = provider.profile.priority,
                            version = provider.profile.version,
                            secretConfigured = provider.profile.secretConfigured,
                            healthStatus = provider.health?.status,
                            healthErrorClass = provider.health?.errorClass,
                            healthCheckedAt = provider.health?.checkedAt,
                        )
                    },
                busyJobId = busyJobId,
                busyProviderProfileId = busyProviderProfileId,
                busyProviderAction = busyProviderAction,
                error = error,
            )
        }
    }

    private data class AssetMetadataEditInternalState(
        val requestId: Long,
        val serverProfileId: ServerProfileId,
        val assetId: String,
        val status: AssetMetadataEditorStatus,
        val title: String,
        val language: String,
        val collectionId: String,
        val version: Long?,
        val error: AssetMetadataEditorError? = null,
        val session: AssetMetadataEditSession? = null,
    ) {
        fun toUiState(): AssetMetadataEditorUiState =
            AssetMetadataEditorUiState(
                assetId = assetId,
                status = status,
                title = title,
                language = language,
                collectionId = collectionId,
                version = version,
                error = error,
            )
    }

    private data class ProfileSelection(
        val savedProfiles: List<ServerProfile>,
        val activeProfileId: ServerProfileId?,
    )

    private class ValidatedProfile(
        val profile: ServerProfile,
        val email: String,
        val password: String,
    ) {
        override fun toString(): String = "ValidatedProfile(profile=$profile, email=$email, password=[REDACTED])"
    }

    class Factory(
        private val profiles: ServerProfileRepository,
        private val activeProfile: ActiveProfileStore,
        private val recordings: RecordingStore,
        private val syncTasks: SyncTaskStore,
        private val transcripts: TranscriptStore,
        private val incrementalSync: IncrementalSyncStore,
        private val syncScheduler: RecordingSyncEnqueuer,
        private val credentials: ServerCredentialStore,
        private val authenticator: ServerProfileAuthenticator,
        private val pairingAuthenticator: DevicePairingAuthenticator = DevicePairingAuthenticator.UNAVAILABLE,
        private val assetMetadataEditor: AssetMetadataEditor,
        private val personalDeviceSessions: PersonalDeviceSessions = PersonalDeviceSessions.NONE,
        private val mobileAdministration: MobileAdministration = MobileAdministration.NONE,
        private val remoteAssetSyncScheduler: RemoteAssetSyncScheduler = RemoteAssetSyncScheduler.NONE,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                "unsupported ViewModel type"
            }
            return MainViewModel(
                profiles = profiles,
                activeProfile = activeProfile,
                recordings = recordings,
                syncTasks = syncTasks,
                transcripts = transcripts,
                incrementalSync = incrementalSync,
                syncScheduler = syncScheduler,
                credentials = credentials,
                authenticator = authenticator,
                pairingAuthenticator = pairingAuthenticator,
                assetMetadataEditor = assetMetadataEditor,
                personalDeviceSessions = personalDeviceSessions,
                mobileAdministration = mobileAdministration,
                remoteAssetSyncScheduler = remoteAssetSyncScheduler,
            ) as T
        }
    }
}

private fun MobileAdministrationSnapshot.withUpdatedProvider(
    previous: MobileAdministrationProviderProfile,
    updated: MobileAdministrationProviderProfile,
): MobileAdministrationSnapshot {
    val delta =
        when {
            previous.profile.state == updated.profile.state -> 0L
            updated.profile.state == ProviderProfileState.ENABLED -> 1L
            else -> -1L
        }
    val providerStatus = systemStatus.providers
    val updatedProviderStatus =
        when (updated.family) {
            com.voiceasset.android.administration.ProviderProfileFamily.ASR ->
                providerStatus.copy(enabledAsr = providerStatus.enabledAsr + delta)
            com.voiceasset.android.administration.ProviderProfileFamily.LLM ->
                providerStatus.copy(enabledLlm = providerStatus.enabledLlm + delta)
        }
    return copy(
        systemStatus = systemStatus.copy(providers = updatedProviderStatus),
        providers = providers.map { provider -> if (provider == previous) updated else provider },
    )
}

private fun MobileAdministrationSnapshot.withProviderHealth(
    previous: MobileAdministrationProviderProfile,
    health: ProviderHealth,
): MobileAdministrationSnapshot =
    copy(
        providers =
            providers.map { provider ->
                if (provider == previous) {
                    provider.copy(health = health)
                } else {
                    provider
                }
            },
    )

private fun MobileAdministrationSnapshot.withRetriedJob(
    previous: AdministrationJob,
    retried: AdministrationJob,
): MobileAdministrationSnapshot {
    if (systemStatus.jobs.failed <= 0) {
        throw VoiceAssetProtocolException("Mobile administration returned inconsistent failed job counts.")
    }
    return copy(
        systemStatus =
            systemStatus.copy(
                jobs =
                    systemStatus.jobs.copy(
                        queued = systemStatus.jobs.queued + 1,
                        failed = systemStatus.jobs.failed - 1,
                    ),
            ),
        jobs = jobs.map { job -> if (job == previous) retried else job },
    )
}

private fun StoredRecordingStatus.isTerminal(): Boolean = this == StoredRecordingStatus.SAVED || this == StoredRecordingStatus.FAILED

private fun StoredRecordingStatus.toUiStatus(): RecordingUiStatus =
    when (this) {
        StoredRecordingStatus.STARTING -> RecordingUiStatus.STARTING
        StoredRecordingStatus.RECORDING -> RecordingUiStatus.RECORDING
        StoredRecordingStatus.PAUSING -> RecordingUiStatus.PAUSING
        StoredRecordingStatus.PAUSED -> RecordingUiStatus.PAUSED
        StoredRecordingStatus.RESUMING -> RecordingUiStatus.RESUMING
        StoredRecordingStatus.STOPPING -> RecordingUiStatus.STOPPING
        StoredRecordingStatus.SAVED -> RecordingUiStatus.SAVED
        StoredRecordingStatus.FAILED -> RecordingUiStatus.FAILED
    }

private fun StoredRecording.syncStatus(task: SyncTask?): SyncUiStatus? {
    if (status != StoredRecordingStatus.SAVED || task == null) {
        return if (status == StoredRecordingStatus.SAVED) SyncUiStatus.PENDING else null
    }
    if (task.blockReason != SyncBlockReason.NONE) {
        return SyncUiStatus.BLOCKED
    }
    return when (task.stage) {
        SyncStage.QUEUED,
        SyncStage.ASSET_CREATED,
        SyncStage.UPLOAD_CREATED,
        -> SyncUiStatus.PENDING
        SyncStage.UPLOADING -> SyncUiStatus.UPLOADING
        SyncStage.UPLOAD_COMPLETED -> SyncUiStatus.UPLOADED
        SyncStage.TRANSCRIPTION_REQUESTED -> SyncUiStatus.TRANSCRIBING
        SyncStage.COMPLETE -> SyncUiStatus.COMPLETE
        SyncStage.FAILED -> SyncUiStatus.FAILED
    }
}

private fun RecordingUiStatus.allowsServerSwitch(): Boolean =
    this == RecordingUiStatus.UNAVAILABLE ||
        this == RecordingUiStatus.READY ||
        this == RecordingUiStatus.SAVED ||
        this == RecordingUiStatus.FAILED

private fun RecordingSession.effectiveUploadPolicy(profile: ServerProfile): UploadPolicy =
    uploadPolicyOverride ?: profile.defaultUploadPolicy

private fun RecordingSession.effectiveTranscriptionPolicy(profile: ServerProfile): TranscriptionPolicy =
    transcriptionPolicyOverride ?: profile.defaultTranscriptionPolicy

internal fun matchesOfflineLibrarySearch(
    query: String,
    vararg values: String?,
): Boolean = query.isBlank() || values.any { value -> value?.contains(query, ignoreCase = true) == true }

private const val MAX_PASSWORD_LENGTH = 1_024
private const val MAX_EMAIL_LENGTH = 254
private const val MAX_PAIRING_PAYLOAD_LENGTH = 2_048
private const val MAX_PAIRED_PROFILE_NAME_LENGTH = 100
private const val DEFAULT_DEVICE_NAME = "VoiceAsset Android"
private const val MAX_CUSTOM_CA_LENGTH = 65_536
private const val MAX_OFFLINE_LIBRARY_SEARCH_LENGTH = 200
private const val MAX_ASSET_TITLE_LENGTH = 500
private const val MAX_ASSET_LANGUAGE_LENGTH = 64
private const val MAX_ASSET_COLLECTION_ID_LENGTH = 100
private const val MAX_SYNCED_ASSETS_ON_HOME = 50
private const val MAX_LOCAL_RECORDINGS_ON_HOME = 50
private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+$")
