package com.voiceasset.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.voiceasset.android.data.CachedRemoteAssetMissingException
import com.voiceasset.android.data.IncrementalSyncCursorConflictException
import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.AssetList
import com.voiceasset.core.api.SyncChangeList
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
abstract class IncrementalSyncDao {
    @Query(
        """
        SELECT * FROM remote_assets
        WHERE server_profile_id = :serverProfileId
        ORDER BY updated_at_epoch_millis DESC, asset_id
        """,
    )
    abstract fun observeAssets(serverProfileId: String): Flow<List<RemoteAssetEntity>>

    @Query("SELECT * FROM remote_assets WHERE server_profile_id = :serverProfileId AND asset_id = :assetId")
    abstract suspend fun findAsset(
        serverProfileId: String,
        assetId: String,
    ): RemoteAssetEntity?

    @Query("SELECT * FROM remote_asset_tombstones WHERE server_profile_id = :serverProfileId AND asset_id = :assetId")
    abstract suspend fun findTombstone(
        serverProfileId: String,
        assetId: String,
    ): RemoteAssetTombstoneEntity?

    @Query("SELECT * FROM incremental_sync_cursors WHERE server_profile_id = :serverProfileId")
    abstract suspend fun findCursor(serverProfileId: String): IncrementalSyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertAsset(entity: RemoteAssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertTombstone(entity: RemoteAssetTombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertCursor(entity: IncrementalSyncCursorEntity)

    @Query("DELETE FROM remote_assets WHERE server_profile_id = :serverProfileId AND asset_id = :assetId")
    protected abstract suspend fun deleteAsset(
        serverProfileId: String,
        assetId: String,
    ): Int

    @Query("DELETE FROM remote_asset_tombstones WHERE server_profile_id = :serverProfileId AND asset_id = :assetId")
    protected abstract suspend fun deleteTombstone(
        serverProfileId: String,
        assetId: String,
    ): Int

    @Transaction
    open suspend fun refreshAsset(
        serverProfileId: ServerProfileId,
        asset: Asset,
    ): RemoteAssetEntity {
        val existing =
            findAsset(serverProfileId.value, asset.id)
                ?: throw CachedRemoteAssetMissingException()
        if (existing.version > asset.version) {
            return existing
        }
        val refreshed = asset.toRemoteAssetEntity(serverProfileId, existing.changeSequence)
        upsertAsset(refreshed)
        deleteTombstone(serverProfileId.value, asset.id)
        return refreshed
    }

    @Transaction
    open suspend fun mergeCatalogPage(
        serverProfileId: ServerProfileId,
        page: AssetList,
    ): Int {
        require(page.items.mapTo(mutableSetOf(), Asset::workspaceId).size <= 1) {
            "asset catalog page spans multiple workspaces"
        }
        var merged = 0
        page.items.forEach { asset ->
            val existing = findAsset(serverProfileId.value, asset.id)
            val tombstone = findTombstone(serverProfileId.value, asset.id)
            if (tombstone != null && tombstone.version >= asset.version) {
                return@forEach
            }
            val candidate = asset.toRemoteAssetEntity(serverProfileId, existing?.changeSequence ?: 0)
            when {
                existing == null || existing.version < candidate.version -> {
                    upsertAsset(candidate)
                    merged += 1
                }
                existing.version == candidate.version ->
                    require(existing.hasSameSnapshot(candidate)) {
                        "asset catalog snapshot changed without a version advance"
                    }
            }
            deleteTombstone(serverProfileId.value, asset.id)
        }
        return merged
    }

    @Transaction
    open suspend fun applyPage(
        serverProfileId: ServerProfileId,
        expectedCursor: String?,
        page: SyncChangeList,
        appliedAtEpochMillis: Long,
    ): IncrementalSyncCursorEntity {
        val current = findCursor(serverProfileId.value)
        if (current?.cursor != expectedCursor || (current == null && expectedCursor != null)) {
            throw IncrementalSyncCursorConflictException()
        }
        var lastSequence = current?.lastSequence ?: 0
        page.items.forEach { change ->
            require(change.sequence > lastSequence) { "sync page sequence did not advance" }
            when (change.operation) {
                "upsert" -> {
                    val currentAsset = findAsset(serverProfileId.value, change.entityId)
                    val currentTombstone = findTombstone(serverProfileId.value, change.entityId)
                    if (
                        currentAsset?.version?.let { it <= change.entityVersion } != false &&
                        currentTombstone?.version?.let { it <= change.entityVersion } != false
                    ) {
                        upsertAsset(change.toRemoteAssetEntity(serverProfileId))
                        deleteTombstone(serverProfileId.value, change.entityId)
                    }
                }
                "delete" -> {
                    require(change.asset == null) { "sync deletion contains an asset snapshot" }
                    val currentAsset = findAsset(serverProfileId.value, change.entityId)
                    val currentTombstone = findTombstone(serverProfileId.value, change.entityId)
                    if (
                        currentAsset?.version?.let { it <= change.entityVersion } != false &&
                        currentTombstone?.version?.let { it <= change.entityVersion } != false
                    ) {
                        deleteAsset(serverProfileId.value, change.entityId)
                        upsertTombstone(
                            RemoteAssetTombstoneEntity(
                                serverProfileId = serverProfileId.value,
                                assetId = change.entityId,
                                version = change.entityVersion,
                                changeSequence = change.sequence,
                                deletedAtEpochMillis = Instant.parse(change.changedAt).toEpochMilli(),
                            ),
                        )
                    }
                }
                else -> error("unsupported sync operation")
            }
            lastSequence = change.sequence
        }
        val updated =
            IncrementalSyncCursorEntity(
                serverProfileId = serverProfileId.value,
                cursor = page.nextCursor,
                lastSequence = lastSequence,
                updatedAtEpochMillis = appliedAtEpochMillis,
            )
        upsertCursor(updated)
        return updated
    }
}
