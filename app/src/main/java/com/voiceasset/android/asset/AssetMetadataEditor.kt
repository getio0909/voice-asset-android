package com.voiceasset.android.asset

import com.voiceasset.android.data.CachedRemoteAsset
import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.android.security.RefreshingServerSessionProvider
import com.voiceasset.android.security.ServerCredentialStore
import com.voiceasset.android.security.ServerSessionAuthenticationRequiredException
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.UpdateAssetMetadataRequest
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId

data class AssetMetadataEditSession(
    val serverProfileId: ServerProfileId,
    val assetId: String,
    val title: String,
    val language: String,
    val collectionId: String?,
    val version: Long,
    val expectedEntityTag: String,
)

interface AssetMetadataEditor {
    suspend fun load(
        serverProfileId: ServerProfileId,
        assetId: String,
    ): AssetMetadataEditSession

    suspend fun save(
        session: AssetMetadataEditSession,
        title: String,
        language: String,
        collectionId: String?,
    ): CachedRemoteAsset
}

class ApiAssetMetadataEditor(
    private val profiles: ServerProfileRepository,
    private val sessions: RefreshingServerSessionProvider,
    private val incrementalSync: IncrementalSyncStore,
) : AssetMetadataEditor {
    constructor(
        profiles: ServerProfileRepository,
        credentials: ServerCredentialStore,
        incrementalSync: IncrementalSyncStore,
        apiFactory: (ServerProfile, BearerCredential?) -> VoiceAssetApi,
    ) : this(
        profiles = profiles,
        sessions = RefreshingServerSessionProvider(credentials, apiFactory),
        incrementalSync = incrementalSync,
    )

    override suspend fun load(
        serverProfileId: ServerProfileId,
        assetId: String,
    ): AssetMetadataEditSession {
        val versioned = createApi(serverProfileId).getAsset(assetId)
        return AssetMetadataEditSession(
            serverProfileId = serverProfileId,
            assetId = versioned.asset.id,
            title = versioned.asset.title,
            language = versioned.asset.language,
            collectionId = versioned.asset.collectionId,
            version = versioned.asset.version,
            expectedEntityTag = versioned.entityTag,
        )
    }

    override suspend fun save(
        session: AssetMetadataEditSession,
        title: String,
        language: String,
        collectionId: String?,
    ): CachedRemoteAsset {
        val updated =
            createApi(session.serverProfileId).updateAssetMetadata(
                assetId = session.assetId,
                expectedEntityTag = session.expectedEntityTag,
                input = UpdateAssetMetadataRequest(title, language, collectionId),
            )
        return incrementalSync.refreshAsset(session.serverProfileId, updated.asset)
    }

    private suspend fun createApi(serverProfileId: ServerProfileId): VoiceAssetApi {
        val profile = profiles.find(serverProfileId) ?: throw AssetMetadataProfileUnavailableException()
        return try {
            sessions.createApi(profile)
        } catch (exception: ServerSessionAuthenticationRequiredException) {
            throw AssetMetadataAuthenticationRequiredException(exception)
        }
    }
}

class AssetMetadataProfileUnavailableException : IllegalStateException("server profile is unavailable")

class AssetMetadataAuthenticationRequiredException(
    cause: Throwable? = null,
) : IllegalStateException("server authentication is required", cause)
