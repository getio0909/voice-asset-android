package com.voiceasset.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ServerProfileEntity::class,
        RecordingEntity::class,
        SyncTaskEntity::class,
        TranscriptEntity::class,
        RemoteAssetEntity::class,
        RemoteAssetTombstoneEntity::class,
        IncrementalSyncCursorEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class VoiceAssetDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao

    abstract fun recordingDao(): RecordingDao

    abstract fun syncTaskDao(): SyncTaskDao

    abstract fun transcriptDao(): TranscriptDao

    abstract fun incrementalSyncDao(): IncrementalSyncDao
}
