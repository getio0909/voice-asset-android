package com.voiceasset.android

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.voiceasset.android.data.IncrementalSyncStore
import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.android.data.SyncTaskStore
import com.voiceasset.android.data.TranscriptStore
import com.voiceasset.android.data.local.MIGRATION_1_2
import com.voiceasset.android.data.local.MIGRATION_2_3
import com.voiceasset.android.data.local.MIGRATION_3_4
import com.voiceasset.android.data.local.RoomIncrementalSyncStore
import com.voiceasset.android.data.local.RoomRecordingStore
import com.voiceasset.android.data.local.RoomServerProfileRepository
import com.voiceasset.android.data.local.RoomSyncTaskStore
import com.voiceasset.android.data.local.RoomTranscriptStore
import com.voiceasset.android.data.local.VoiceAssetDatabase
import com.voiceasset.android.data.preferences.ActiveServerProfileStore
import com.voiceasset.android.security.AndroidKeystoreCredentialStore
import com.voiceasset.android.security.ServerCredentialStore
import com.voiceasset.android.sync.IncrementalSyncScheduler
import com.voiceasset.android.sync.RecordingSyncScheduler

class AppContainer(
    application: Application,
) {
    private val database =
        Room
            .databaseBuilder(application, VoiceAssetDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    private val settingsDataStore =
        PreferenceDataStoreFactory.create {
            application.preferencesDataStoreFile(SETTINGS_DATASTORE_NAME)
        }
    private val credentialDataStore =
        PreferenceDataStoreFactory.create {
            application.preferencesDataStoreFile(CREDENTIAL_DATASTORE_NAME)
        }

    val serverProfiles: ServerProfileRepository =
        RoomServerProfileRepository(database.serverProfileDao())
    val recordings: RecordingStore = RoomRecordingStore(database.recordingDao())
    val syncTasks: SyncTaskStore = RoomSyncTaskStore(database.syncTaskDao())
    val transcripts: TranscriptStore = RoomTranscriptStore(database.transcriptDao())
    val incrementalSync: IncrementalSyncStore = RoomIncrementalSyncStore(database.incrementalSyncDao())
    val activeServerProfile = ActiveServerProfileStore(settingsDataStore)
    val credentials: ServerCredentialStore = AndroidKeystoreCredentialStore(credentialDataStore)
    val syncScheduler = RecordingSyncScheduler(application)
    val incrementalSyncScheduler = IncrementalSyncScheduler(application)

    private companion object {
        const val DATABASE_NAME = "voiceasset.db"
        const val SETTINGS_DATASTORE_NAME = "voiceasset-settings"
        const val CREDENTIAL_DATASTORE_NAME = "voiceasset-server-credentials"
    }
}
