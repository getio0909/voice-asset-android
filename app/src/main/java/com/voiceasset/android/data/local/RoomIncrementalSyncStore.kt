package com.voiceasset.android.data.local

import com.voiceasset.android.data.CachedRemoteAsset
import com.voiceasset.android.data.IncrementalSyncCheckpoint
import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomIncrementalSyncStore(
    private val dao: IncrementalSyncDao,
) : IncrementalSyncStore {
    override fun observeAssets(serverProfileId: ServerProfileId): Flow<List<CachedRemoteAsset>> =
        dao.observeAssets(serverProfileId.value).map { entities -> entities.map(RemoteAssetEntity::toDomain) }

    override suspend fun checkpoint(serverProfileId: ServerProfileId): IncrementalSyncCheckpoint? =
        dao.findCursor(serverProfileId.value)?.toDomain()

    override suspend fun refreshAsset(
        serverProfileId: ServerProfileId,
        asset: Asset,
    ): CachedRemoteAsset = dao.refreshAsset(serverProfileId, asset).toDomain()

    override suspend fun mergeCatalogPage(
        serverProfileId: ServerProfileId,
        page: AssetList,
    ): Int = dao.mergeCatalogPage(serverProfileId, page)

    override suspend fun applyPage(
        serverProfileId: ServerProfileId,
        expectedCursor: String?,
        page: SyncChangeList,
        appliedAtEpochMillis: Long,
    ): IncrementalSyncCheckpoint =
        dao
            .applyPage(serverProfileId, expectedCursor, page, appliedAtEpochMillis)
            .toDomain()
}
