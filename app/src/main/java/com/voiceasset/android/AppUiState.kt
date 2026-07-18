package com.voiceasset.android

import com.voiceasset.android.administration.ProviderProfileFamily
import com.voiceasset.core.api.ProviderHealthErrorClass
import com.voiceasset.core.api.ProviderHealthStatus
import com.voiceasset.core.api.ProviderProfileState
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy

data class AppUiState(
    val initializationStatus: InitializationStatus,
    val serverStatus: ServerStatus,
    val serverProfiles: List<ServerProfileSummary>,
    val activeServerProfileId: String?,
    val serverDraft: ServerProfileDraft,
    val isSavingServer: Boolean,
    val serverFormError: ServerProfileFormError?,
    val recordingStatus: RecordingUiStatus,
    val recordingError: String?,
    val recordingUploadPolicyOverride: UploadPolicy?,
    val recordingTranscriptionPolicyOverride: TranscriptionPolicy?,
    val offlineLibrarySearchQuery: String,
    val localRecordingCount: Int,
    val localRecordingMatchCount: Int,
    val localRecordings: List<LocalRecordingSummary>,
    val transcriptRevisionId: String?,
    val transcriptLanguage: String?,
    val transcriptText: String?,
    val activeRecordingTranscriptionPolicy: TranscriptionPolicy?,
    val syncedAssetCount: Int,
    val syncedAssetMatchCount: Int,
    val syncedAssets: List<SyncedAssetSummary>,
    val assetMetadataEditor: AssetMetadataEditorUiState? = null,
    val serverSessionReconnect: ServerSessionReconnectUiState = ServerSessionReconnectUiState(),
    val deviceSessions: DeviceSessionsUiState = DeviceSessionsUiState(),
    val mobileAdministration: MobileAdministrationUiState = MobileAdministrationUiState(),
)

data class ServerSessionReconnectUiState(
    val status: ServerSessionReconnectStatus = ServerSessionReconnectStatus.IDLE,
    val email: String = "",
    val password: SecretInput = SecretInput.EMPTY,
    val error: ServerSessionReconnectError? = null,
)

enum class ServerSessionReconnectStatus {
    IDLE,
    SUBMITTING,
    SUCCEEDED,
    FAILED,
}

enum class ServerSessionReconnectError {
    INVALID_EMAIL,
    INVALID_PASSWORD,
    AUTHENTICATION_FAILED,
    PROFILE_UNAVAILABLE,
    CONNECTION_FAILED,
    TLS_FAILED,
    PROTOCOL_MISMATCH,
    SECURE_STORAGE_FAILED,
    RECONNECT_FAILED,
}

data class DeviceSessionsUiState(
    val status: DeviceSessionsStatus = DeviceSessionsStatus.IDLE,
    val items: List<DeviceSessionSummary> = emptyList(),
    val pendingRevocationId: String? = null,
    val revokingId: String? = null,
    val error: DeviceSessionsError? = null,
)

data class DeviceSessionSummary(
    val id: String,
    val deviceName: String,
    val current: Boolean,
    val lastSeenAt: String,
    val refreshExpiresAt: String,
)

enum class DeviceSessionsStatus {
    IDLE,
    LOADING,
    READY,
    FAILED,
}

enum class DeviceSessionsError {
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    PROFILE_UNAVAILABLE,
    NOT_FOUND,
    CONNECTION_FAILED,
    TLS_FAILED,
    PROTOCOL_MISMATCH,
    SECURE_STORAGE_FAILED,
    LOAD_FAILED,
    REVOKE_FAILED,
}

data class MobileAdministrationUiState(
    val status: MobileAdministrationStatus = MobileAdministrationStatus.IDLE,
    val systemStatus: MobileSystemStatusSummary? = null,
    val jobs: List<MobileAdministrationJobSummary> = emptyList(),
    val providers: List<MobileProviderProfileSummary> = emptyList(),
    val busyJobId: String? = null,
    val busyProviderProfileId: String? = null,
    val busyProviderAction: MobileProviderAction? = null,
    val error: MobileAdministrationError? = null,
)

enum class MobileProviderAction {
    STATE_UPDATE,
    HEALTH_CHECK,
}

enum class MobileAdministrationStatus {
    IDLE,
    LOADING,
    READY,
    FAILED,
}

data class MobileSystemStatusSummary(
    val generatedAt: String,
    val activeUsers: Long,
    val assetCount: Long,
    val storageObjectCount: Long,
    val storageBytes: Long,
    val transcriptCount: Long,
    val revisionCount: Long,
    val jobCount: Long,
    val queuedJobCount: Long,
    val runningJobCount: Long,
    val retryWaitJobCount: Long,
    val failedJobCount: Long,
    val enabledAsrCount: Long,
    val enabledLlmCount: Long,
)

data class MobileAdministrationJobSummary(
    val id: String,
    val kind: String,
    val state: String,
    val attempts: Int,
    val maxAttempts: Int,
    val retryable: Boolean,
    val lastErrorCode: String?,
    val updatedAt: String,
)

data class MobileProviderProfileSummary(
    val id: String,
    val family: ProviderProfileFamily,
    val providerId: String,
    val displayName: String,
    val state: ProviderProfileState,
    val priority: Int,
    val version: Long,
    val secretConfigured: Boolean,
    val healthStatus: ProviderHealthStatus? = null,
    val healthErrorClass: ProviderHealthErrorClass? = null,
    val healthCheckedAt: String? = null,
)

enum class MobileAdministrationError {
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    PROFILE_UNAVAILABLE,
    CONFLICT,
    CONNECTION_FAILED,
    TLS_FAILED,
    PROTOCOL_MISMATCH,
    SECURE_STORAGE_FAILED,
    LOAD_FAILED,
    UPDATE_FAILED,
    HEALTH_CHECK_FAILED,
    JOB_NOT_RETRYABLE,
    JOB_RETRY_FAILED,
}

data class ServerProfileSummary(
    val id: String,
    val name: String,
    val origin: String,
    val uploadPolicy: UploadPolicy = UploadPolicy.WIFI_ONLY,
    val transcriptionPolicy: TranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
    val isActive: Boolean = false,
)

data class SyncedAssetSummary(
    val id: String,
    val title: String,
    val language: String,
    val status: String,
    val durationMillis: Long?,
    val version: Long,
    val isTrashed: Boolean,
    val collectionId: String? = null,
)

data class AssetMetadataEditorUiState(
    val assetId: String,
    val status: AssetMetadataEditorStatus,
    val title: String,
    val language: String,
    val collectionId: String,
    val version: Long?,
    val error: AssetMetadataEditorError?,
)

enum class AssetMetadataEditorStatus {
    LOADING,
    EDITING,
    SAVING,
    SAVED,
    FAILED,
}

enum class AssetMetadataEditorError {
    INVALID_INPUT,
    AUTHENTICATION_REQUIRED,
    PERMISSION_DENIED,
    NOT_FOUND,
    CONFLICT,
    CONNECTION_FAILED,
    TLS_FAILED,
    PROTOCOL_MISMATCH,
    SECURE_STORAGE_FAILED,
    LOAD_FAILED,
    SAVE_FAILED,
}

data class LocalRecordingSummary(
    val id: String,
    val fileName: String,
    val recordingStatus: RecordingUiStatus,
    val durationMillis: Long?,
    val syncStatus: SyncUiStatus?,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val hasTranscript: Boolean,
    val errorCode: String?,
    val uploadPolicy: UploadPolicy? = null,
    val transcriptionPolicy: TranscriptionPolicy? = null,
    val hasUploadPolicyOverride: Boolean = false,
    val hasTranscriptionPolicyOverride: Boolean = false,
    val canStartUpload: Boolean = false,
    val canStartTranscription: Boolean = false,
    val canPlay: Boolean = false,
    val canExport: Boolean = false,
)

data class ServerProfileDraft(
    val name: String = "",
    val baseUrl: String = "",
    val email: String = "",
    val password: SecretInput = SecretInput.EMPTY,
    val pairingPayload: SecretInput = SecretInput.EMPTY,
    val customCaPem: String = "",
    val certificateFingerprint: String = "",
    val uploadPolicy: UploadPolicy = UploadPolicy.WIFI_ONLY,
    val transcriptionPolicy: TranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
)

class SecretInput private constructor(
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is SecretInput && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SecretInput([REDACTED])"

    companion object {
        val EMPTY = SecretInput("")

        fun of(value: String): SecretInput = if (value.isEmpty()) EMPTY else SecretInput(value)
    }
}

enum class InitializationStatus {
    INITIALIZED,
}

enum class ServerStatus {
    NOT_CONFIGURED,
    CONFIGURED,
}

enum class ServerProfileFormError {
    INVALID_NAME,
    INVALID_URL,
    INVALID_EMAIL,
    INVALID_PASSWORD,
    INVALID_PAIRING,
    INVALID_CUSTOM_CA,
    INVALID_FINGERPRINT,
    AUTHENTICATION_FAILED,
    CONNECTION_FAILED,
    TLS_FAILED,
    INCOMPATIBLE_SERVER,
    SECURE_STORAGE_FAILED,
    SAVE_FAILED,
}

enum class RecordingUiStatus {
    UNAVAILABLE,
    READY,
    STARTING,
    RECORDING,
    PAUSING,
    PAUSED,
    RESUMING,
    STOPPING,
    SAVED,
    FAILED,
}

enum class SyncUiStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    TRANSCRIBING,
    BLOCKED,
    COMPLETE,
    FAILED,
}

fun initialAppUiState(): AppUiState =
    AppUiState(
        initializationStatus = InitializationStatus.INITIALIZED,
        serverStatus = ServerStatus.NOT_CONFIGURED,
        serverProfiles = emptyList(),
        activeServerProfileId = null,
        serverDraft = ServerProfileDraft(),
        isSavingServer = false,
        serverFormError = null,
        recordingStatus = RecordingUiStatus.READY,
        recordingError = null,
        recordingUploadPolicyOverride = null,
        recordingTranscriptionPolicyOverride = null,
        offlineLibrarySearchQuery = "",
        localRecordingCount = 0,
        localRecordingMatchCount = 0,
        localRecordings = emptyList(),
        transcriptRevisionId = null,
        transcriptLanguage = null,
        transcriptText = null,
        activeRecordingTranscriptionPolicy = null,
        syncedAssetCount = 0,
        syncedAssetMatchCount = 0,
        syncedAssets = emptyList(),
        assetMetadataEditor = null,
        serverSessionReconnect = ServerSessionReconnectUiState(),
        deviceSessions = DeviceSessionsUiState(),
        mobileAdministration = MobileAdministrationUiState(),
    )
