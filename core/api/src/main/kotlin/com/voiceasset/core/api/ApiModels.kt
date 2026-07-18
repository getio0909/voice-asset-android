package com.voiceasset.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class BearerCredential(
    val value: String,
) {
    init {
        require(value.startsWith("va_") && value.length >= 20) {
            "bearer credential must be a VoiceAsset opaque token"
        }
        require(value.none(Char::isWhitespace) && value.none(Char::isISOControl)) {
            "bearer credential must not contain whitespace or control characters"
        }
    }

    override fun toString(): String = "BearerCredential([REDACTED])"
}

class RefreshCredential(
    val value: String,
) {
    init {
        require(value.startsWith(REFRESH_TOKEN_PREFIX) && value.length == REFRESH_TOKEN_LENGTH) {
            "refresh credential must be a VoiceAsset opaque refresh token"
        }
        require(value.none(Char::isWhitespace) && value.none(Char::isISOControl)) {
            "refresh credential must not contain whitespace or control characters"
        }
    }

    override fun toString(): String = "RefreshCredential([REDACTED])"
}

@Serializable
data class ServerCapabilities(
    @SerialName("server_version")
    val serverVersion: String,
    @SerialName("api_version")
    val apiVersion: String,
    @SerialName("contract_version")
    val contractVersion: String,
    val features: List<String>,
)

@Serializable
data class AdministrationJobList(
    val items: List<AdministrationJob>,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
)

@Serializable
data class AdministrationJob(
    val id: String,
    @SerialName("asset_id")
    val assetId: String? = null,
    @SerialName("created_by")
    val createdBy: String,
    val kind: String,
    val state: String,
    val attempts: Int,
    @SerialName("max_attempts")
    val maxAttempts: Int,
    val retryable: Boolean = false,
    @SerialName("available_at")
    val availableAt: String,
    @SerialName("lease_expires_at")
    val leaseExpiresAt: String? = null,
    @SerialName("last_error_code")
    val lastErrorCode: String? = null,
    @SerialName("result_revision_id")
    val resultRevisionId: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class AdministrationSystemStatus(
    @SerialName("generated_at")
    val generatedAt: String,
    @SerialName("active_users")
    val activeUsers: Long,
    val assets: AdministrationAssetStatus,
    val storage: AdministrationStorageStatus,
    val transcripts: AdministrationTranscriptStatus,
    val jobs: AdministrationJobStatus,
    val providers: AdministrationProviderStatus,
)

@Serializable
data class AdministrationAssetStatus(
    val total: Long,
    val active: Long,
    val trashed: Long,
    val purging: Long,
    val failed: Long,
    @SerialName("audio_duration_ms")
    val audioDurationMillis: Long,
)

@Serializable
data class AdministrationStorageStatus(
    @SerialName("object_count")
    val objectCount: Long,
    val bytes: Long,
)

@Serializable
data class AdministrationTranscriptStatus(
    @SerialName("transcript_count")
    val transcriptCount: Long,
    @SerialName("revision_count")
    val revisionCount: Long,
)

@Serializable
data class AdministrationJobStatus(
    val total: Long,
    val queued: Long,
    val running: Long,
    @SerialName("retry_wait")
    val retryWait: Long,
    val succeeded: Long,
    val failed: Long,
    val cancelled: Long,
)

@Serializable
data class AdministrationProviderStatus(
    @SerialName("enabled_asr")
    val enabledAsr: Long,
    @SerialName("enabled_llm")
    val enabledLlm: Long,
)

@Serializable
enum class ProviderProfileState {
    @SerialName("enabled")
    ENABLED,

    @SerialName("disabled")
    DISABLED,
}

@Serializable
enum class ProviderHealthStatus {
    @SerialName("healthy")
    HEALTHY,

    @SerialName("unhealthy")
    UNHEALTHY,
}

@Serializable
enum class ProviderHealthErrorClass {
    @SerialName("invalid_configuration")
    INVALID_CONFIGURATION,

    @SerialName("authentication")
    AUTHENTICATION,

    @SerialName("authorization")
    AUTHORIZATION,

    @SerialName("rate_limited")
    RATE_LIMITED,

    @SerialName("invalid_audio")
    INVALID_AUDIO,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("transient")
    TRANSIENT,

    @SerialName("rejected")
    REJECTED,

    @SerialName("unsafe_proposal")
    UNSAFE_PROPOSAL,

    @SerialName("canceled")
    CANCELED,
}

@Serializable
data class ProviderHealth(
    @SerialName("profile_id")
    val profileId: String,
    val status: ProviderHealthStatus,
    @SerialName("error_class")
    val errorClass: ProviderHealthErrorClass? = null,
    @SerialName("checked_at")
    val checkedAt: String,
)

@Serializable
data class ProviderProfile(
    val id: String,
    @SerialName("workspace_id")
    val workspaceId: String,
    @SerialName("provider_id")
    val providerId: String,
    @SerialName("display_name")
    val displayName: String,
    val config: JsonObject,
    val state: ProviderProfileState,
    val priority: Int,
    val version: Long,
    @SerialName("secret_configured")
    val secretConfigured: Boolean,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class ProviderProfileList(
    val items: List<ProviderProfile>,
)

data class VersionedProviderProfile(
    val profile: ProviderProfile,
    val entityTag: String,
)

@Serializable
internal data class UpdateProviderProfileStateRequest(
    val state: ProviderProfileState,
)

@Serializable
data class Principal(
    val id: String,
    @SerialName("workspace_id")
    val workspaceId: String,
    val role: String,
    val email: String,
    val scopes: List<String>,
)

@Serializable
data class WebSession(
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("refresh_expires_at")
    val refreshExpiresAt: String,
    val user: Principal,
)

@Serializable
data class DeviceSession(
    val id: String,
    @SerialName("device_name")
    val deviceName: String,
    val current: Boolean,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("last_seen_at")
    val lastSeenAt: String,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("refresh_expires_at")
    val refreshExpiresAt: String,
    @SerialName("revoked_at")
    val revokedAt: String? = null,
)

@Serializable
data class DeviceSessionList(
    val items: List<DeviceSession>,
)

data class LoginResult(
    val session: WebSession,
    val credential: BearerCredential,
    val refreshCredential: RefreshCredential,
)

@Serializable
internal data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_name")
    val deviceName: String,
) {
    override fun toString(): String = "LoginRequest(email=$email, password=[REDACTED], deviceName=$deviceName)"
}

@Serializable
internal data class PairingClaimRequest(
    val secret: String,
    @SerialName("device_name")
    val deviceName: String,
) {
    override fun toString(): String = "PairingClaimRequest(secret=[REDACTED], deviceName=$deviceName)"
}

@Serializable
internal data class ChangePasswordRequest(
    @SerialName("current_password")
    val currentPassword: String,
    @SerialName("new_password")
    val newPassword: String,
) {
    override fun toString(): String = "ChangePasswordRequest(currentPassword=[REDACTED], newPassword=[REDACTED])"
}

private const val REFRESH_TOKEN_PREFIX = "va_rft_"
private const val REFRESH_TOKEN_LENGTH = 50

@Serializable
data class CreateAssetRequest(
    val title: String,
    val language: String,
)

@Serializable
data class UpdateAssetMetadataRequest(
    val title: String,
    val language: String,
    @SerialName("collection_id")
    val collectionId: String?,
)

@Serializable
data class Asset(
    val id: String,
    @SerialName("workspace_id")
    val workspaceId: String,
    @SerialName("collection_id")
    val collectionId: String?,
    val title: String,
    val language: String,
    val status: String,
    @SerialName("duration_ms")
    val durationMillis: Long?,
    val version: Long,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class AssetList(
    val items: List<Asset>,
    @SerialName("next_cursor")
    val nextCursor: String? = null,
)

data class VersionedAsset(
    val asset: Asset,
    val entityTag: String,
)

@Serializable
data class SyncAssetSnapshot(
    val id: String,
    @SerialName("collection_id")
    val collectionId: String?,
    val title: String,
    val language: String,
    val status: String,
    @SerialName("duration_ms")
    val durationMillis: Long?,
    val version: Long,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("trashed_at")
    val trashedAt: String?,
)

@Serializable
data class SyncChange(
    val sequence: Long,
    @SerialName("entity_type")
    val entityType: String,
    @SerialName("entity_id")
    val entityId: String,
    val operation: String,
    @SerialName("entity_version")
    val entityVersion: Long,
    @SerialName("changed_at")
    val changedAt: String,
    val asset: SyncAssetSnapshot? = null,
)

@Serializable
data class SyncChangeList(
    val items: List<SyncChange>,
    @SerialName("next_cursor")
    val nextCursor: String,
    @SerialName("has_more")
    val hasMore: Boolean,
)

@Serializable
data class CreateUploadRequest(
    @SerialName("asset_id")
    val assetId: String,
    val filename: String,
    @SerialName("mime_type")
    val mimeType: String,
    @SerialName("size_bytes")
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class UploadSession(
    val id: String,
    @SerialName("asset_id")
    val assetId: String,
    @SerialName("workspace_id")
    val workspaceId: String,
    val filename: String,
    @SerialName("mime_type")
    val mimeType: String,
    @SerialName("expected_size")
    val expectedSize: Long,
    @SerialName("expected_sha256")
    val expectedSha256: String,
    @SerialName("part_size")
    val partSize: Int,
    val state: String,
    @SerialName("expires_at")
    val expiresAt: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("completed_at")
    val completedAt: String?,
    @SerialName("error_code")
    val errorCode: String?,
    val parts: List<UploadPart>?,
)

@Serializable
data class UploadPart(
    val number: Int,
    @SerialName("size_bytes")
    val sizeBytes: Long,
    val sha256: String,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class TranscriptionJobPayload(
    @SerialName("asset_id")
    val assetId: String,
)

@Serializable
data class TranscriptionJob(
    val id: String,
    @SerialName("workspace_id")
    val workspaceId: String,
    @SerialName("asset_id")
    val assetId: String,
    @SerialName("created_by")
    val createdBy: String,
    val kind: String,
    val state: String,
    val payload: TranscriptionJobPayload,
    val attempts: Int,
    @SerialName("max_attempts")
    val maxAttempts: Int,
    @SerialName("available_at")
    val availableAt: String,
    @SerialName("lease_owner")
    val leaseOwner: String? = null,
    @SerialName("lease_expires_at")
    val leaseExpiresAt: String? = null,
    @SerialName("last_error_code")
    val lastErrorCode: String? = null,
    @SerialName("result_revision_id")
    val resultRevisionId: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
)

@Serializable
data class TranscriptList(
    val items: List<TranscriptSummary>,
)

@Serializable
data class TranscriptSummary(
    val id: String,
    @SerialName("asset_id")
    val assetId: String,
    val language: String,
    @SerialName("latest_revision_id")
    val latestRevisionId: String,
    @SerialName("latest_kind")
    val latestKind: String,
    @SerialName("latest_text")
    val latestText: String,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("revision_created_at")
    val revisionCreatedAt: String,
)

@Serializable
data class TranscriptRevision(
    val id: String,
    @SerialName("transcript_id")
    val transcriptId: String,
    @SerialName("asset_id")
    val assetId: String,
    @SerialName("parent_revision_id")
    val parentRevisionId: String? = null,
    val kind: String,
    val language: String,
    val text: String,
    @SerialName("provider_snapshot")
    val providerSnapshot: JsonObject,
    @SerialName("hotword_snapshot")
    val hotwordSnapshot: JsonObject,
    @SerialName("glossary_snapshot")
    val glossarySnapshot: JsonObject,
    val diff: JsonObject,
    @SerialName("validation_result")
    val validationResult: JsonObject,
    @SerialName("provider_raw_object_id")
    val providerRawObjectId: String? = null,
    @SerialName("source_job_id")
    val sourceJobId: String? = null,
    @SerialName("created_by")
    val createdBy: String? = null,
    @SerialName("created_by_type")
    val createdByType: String,
    val model: String? = null,
    @SerialName("prompt_version")
    val promptVersion: String? = null,
    @SerialName("review_status")
    val reviewStatus: String,
    @SerialName("created_at")
    val createdAt: String,
    val segments: List<TranscriptSegment>,
)

@Serializable
data class TranscriptSegment(
    val id: String,
    val ordinal: Int,
    @SerialName("start_ms")
    val startMillis: Long,
    @SerialName("end_ms")
    val endMillis: Long,
    val speaker: String?,
    val text: String,
    val confidence: Double?,
    val words: List<JsonObject>,
)

@Serializable
internal data class ErrorEnvelope(
    val error: ApiError,
)

@Serializable
internal data class ApiError(
    val code: String,
    val message: String,
    @SerialName("request_id")
    val requestId: String,
)
