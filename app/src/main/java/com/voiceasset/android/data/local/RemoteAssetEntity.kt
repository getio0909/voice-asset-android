package com.voiceasset.android.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.voiceasset.android.data.CachedRemoteAsset
import com.voiceasset.core.api.Asset
import com.voiceasset.core.api.SyncChange
import com.voiceasset.core.model.ServerProfileId
import java.time.Instant

@Entity(
    tableName = "remote_assets",
    primaryKeys = ["server_profile_id", "asset_id"],
    foreignKeys = [
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["server_profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["server_profile_id", "updated_at_epoch_millis"]),
    ],
)
data class RemoteAssetEntity(
    @ColumnInfo(name = "server_profile_id")
    val serverProfileId: String,
    @ColumnInfo(name = "asset_id")
    val assetId: String,
    @ColumnInfo(name = "collection_id")
    val collectionId: String?,
    val title: String,
    val language: String,
    val status: String,
    @ColumnInfo(name = "duration_ms")
    val durationMillis: Long?,
    val version: Long,
    @ColumnInfo(name = "change_sequence")
    val changeSequence: Long,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "trashed_at_epoch_millis")
    val trashedAtEpochMillis: Long?,
)

internal fun SyncChange.toRemoteAssetEntity(serverProfileId: ServerProfileId): RemoteAssetEntity {
    val snapshot = requireNotNull(asset) { "sync upsert is missing its asset snapshot" }
    require(operation == "upsert" && snapshot.id == entityId && snapshot.version == entityVersion) {
        "sync upsert identifiers are inconsistent"
    }
    return RemoteAssetEntity(
        serverProfileId = serverProfileId.value,
        assetId = entityId,
        collectionId = snapshot.collectionId,
        title = snapshot.title,
        language = snapshot.language,
        status = snapshot.status,
        durationMillis = snapshot.durationMillis,
        version = entityVersion,
        changeSequence = sequence,
        createdAtEpochMillis = Instant.parse(snapshot.createdAt).toEpochMilli(),
        updatedAtEpochMillis = Instant.parse(snapshot.updatedAt).toEpochMilli(),
        trashedAtEpochMillis = snapshot.trashedAt?.let { value -> Instant.parse(value).toEpochMilli() },
    )
}

internal fun Asset.toRemoteAssetEntity(
    serverProfileId: ServerProfileId,
    changeSequence: Long,
): RemoteAssetEntity {
    require(version >= 1 && durationMillis?.let { it >= 0 } != false) { "asset snapshot is invalid" }
    val createdAt = Instant.parse(createdAt).toEpochMilli()
    val updatedAt = Instant.parse(updatedAt).toEpochMilli()
    require(updatedAt >= createdAt) { "asset timestamps are invalid" }
    return RemoteAssetEntity(
        serverProfileId = serverProfileId.value,
        assetId = id,
        collectionId = collectionId,
        title = title,
        language = language,
        status = status,
        durationMillis = durationMillis,
        version = version,
        changeSequence = changeSequence,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = updatedAt,
        trashedAtEpochMillis = null,
    )
}

internal fun RemoteAssetEntity.toDomain(): CachedRemoteAsset =
    CachedRemoteAsset(
        serverProfileId = ServerProfileId.parse(serverProfileId),
        assetId = assetId,
        collectionId = collectionId,
        title = title,
        language = language,
        status = status,
        durationMillis = durationMillis,
        version = version,
        changeSequence = changeSequence,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        trashedAtEpochMillis = trashedAtEpochMillis,
    )

internal fun RemoteAssetEntity.hasSameSnapshot(other: RemoteAssetEntity): Boolean =
    serverProfileId == other.serverProfileId &&
        assetId == other.assetId &&
        collectionId == other.collectionId &&
        title == other.title &&
        language == other.language &&
        status == other.status &&
        durationMillis == other.durationMillis &&
        version == other.version &&
        createdAtEpochMillis == other.createdAtEpochMillis &&
        updatedAtEpochMillis == other.updatedAtEpochMillis &&
        trashedAtEpochMillis == other.trashedAtEpochMillis
