package com.voiceasset.android

import android.app.Application
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.android.recording.MediaRecorderEngine
import com.voiceasset.android.recording.RecordingEngine
import com.voiceasset.android.recording.RecordingRecovery
import com.voiceasset.android.security.RefreshingServerSessionProvider
import com.voiceasset.android.security.StartupSyncPolicy
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.api.VoiceAssetApiClient
import com.voiceasset.core.model.ServerProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

open class VoiceAssetApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Allows the instrumentation application to opt out of process-start work while seeding fixtures. */
    protected open val enableStartupRecoveryAndSync: Boolean = true

    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
    val serverSessions: RefreshingServerSessionProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RefreshingServerSessionProvider(container.credentials, ::createApiClient)
    }

    override fun onCreate() {
        super.onCreate()
        if (!enableStartupRecoveryAndSync) {
            return
        }
        applicationScope.launch {
            RecordingRecovery(
                recordingDirectory = filesDir.resolve("recordings"),
                recordingStore = container.recordings,
            ).recoverInterrupted()
            val savedProfiles = container.serverProfiles.observeAll().first()
            val authenticatedProfileIds = StartupSyncPolicy(container.credentials).authenticatedProfileIds(savedProfiles)
            authenticatedProfileIds.forEach { profileId ->
                container.incrementalSyncScheduler.schedule(profileId)
            }
            container.recordings
                .observeAll()
                .first()
                .filter { recording -> recording.status == StoredRecordingStatus.SAVED }
                .forEach { recording ->
                    val profileId = recording.session.serverProfileId ?: return@forEach
                    if (profileId !in authenticatedProfileIds) {
                        return@forEach
                    }
                    val profile = container.serverProfiles.find(profileId) ?: return@forEach
                    container.syncScheduler.enqueue(recording.session, profile)
                }
        }
    }

    open fun createRecordingEngine(): RecordingEngine = MediaRecorderEngine(this)

    open fun createApiClient(
        profile: ServerProfile,
        credential: BearerCredential?,
    ): VoiceAssetApi = VoiceAssetApiClient.forProfile(profile, credential)
}
