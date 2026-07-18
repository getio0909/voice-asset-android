package com.voiceasset.android.data

import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow

data class CachedRemoteAsset(
    val serverProfileId: ServerProfileId,
    val assetId: String,
    val collectionId: String?,
    val title: String,
    val language: String,
    val status: String,
    val durationMillis: Long?,
    val version: Long,
    val changeSequence: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val trashedAtEpochMillis: Long?,
)

data class IncrementalSyncCheckpoint(
    val serverProfileId: ServerProfileId,
    val cursor: String,
    val lastSequence: Long,
    val updatedAtEpochMillis: Long,
)

interface IncrementalSyncStore {
    fun observeAssets(serverProfileId: ServerProfileId): Flow<List<CachedRemoteAsset>>

    suspend fun checkpoint(serverProfileId: ServerProfileId): IncrementalSyncCheckpoint?

    suspend fun refreshAsset(
        serverProfileId: ServerProfileId,
        asset: Asset,
    ): CachedRemoteAsset

    suspend fun mergeCatalogPage(
        serverProfileId: ServerProfileId,
        page: AssetList,
    ): Int

    suspend fun applyPage(
        serverProfileId: ServerProfileId,
        expectedCursor: String?,
        page: SyncChangeList,
        appliedAtEpochMillis: Long,
    ): IncrementalSyncCheckpoint
}

class IncrementalSyncCursorConflictException : IllegalStateException("incremental sync cursor changed concurrently")

class CachedRemoteAssetMissingException : IllegalStateException("cached remote asset no longer exists")
