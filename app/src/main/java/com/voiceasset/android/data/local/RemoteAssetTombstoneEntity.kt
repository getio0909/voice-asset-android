package com.voiceasset.android.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "remote_asset_tombstones",
    primaryKeys = ["server_profile_id", "asset_id"],
    foreignKeys = [
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["server_profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["server_profile_id", "deleted_at_epoch_millis"])],
)
data class RemoteAssetTombstoneEntity(
    @ColumnInfo(name = "server_profile_id")
    val serverProfileId: String,
    @ColumnInfo(name = "asset_id")
    val assetId: String,
    val version: Long,
    @ColumnInfo(name = "change_sequence")
    val changeSequence: Long,
    @ColumnInfo(name = "deleted_at_epoch_millis")
    val deletedAtEpochMillis: Long,
)
