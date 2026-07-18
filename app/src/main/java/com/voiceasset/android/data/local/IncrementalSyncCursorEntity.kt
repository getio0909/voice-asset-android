package com.voiceasset.android.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.voiceasset.android.data.IncrementalSyncCheckpoint
import com.voiceasset.core.model.ServerProfileId

@Entity(
    tableName = "incremental_sync_cursors",
    foreignKeys = [
        ForeignKey(
            entity = ServerProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["server_profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class IncrementalSyncCursorEntity(
    @PrimaryKey
    @ColumnInfo(name = "server_profile_id")
    val serverProfileId: String,
    val cursor: String,
    @ColumnInfo(name = "last_sequence")
    val lastSequence: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

internal fun IncrementalSyncCursorEntity.toDomain(): IncrementalSyncCheckpoint =
    IncrementalSyncCheckpoint(
        serverProfileId = ServerProfileId.parse(serverProfileId),
        cursor = cursor,
        lastSequence = lastSequence,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
