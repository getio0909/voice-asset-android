package com.voiceasset.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.voiceasset.android.administration.ApiMobileAdministration
import com.voiceasset.android.asset.ApiAssetMetadataEditor
import com.voiceasset.android.export.RecordingExporter
import com.voiceasset.android.playback.RecordingPlaybackController
import com.voiceasset.android.playback.createRecordingPlaybackController
import com.voiceasset.android.recording.RecordingService
import com.voiceasset.android.security.ApiPersonalDeviceSessions
import com.voiceasset.core.api.ApiDevicePairingAuthenticator
import com.voiceasset.core.api.ApiServerProfileAuthenticator
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingRecordingRequest: PendingRecordingRequest? = null
    private lateinit var recordingPlayback: RecordingPlaybackController
    private var noisyReceiverRegistered = false
    private val noisyReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (
                    intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
                    ::recordingPlayback.isInitialized
                ) {
                    recordingPlayback.pause()
                }
            }
        }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val microphoneGranted =
                grants[Manifest.permission.RECORD_AUDIO] == true ||
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val request = pendingRecordingRequest
            pendingRecordingRequest = null
            if (microphoneGranted && request != null) {
                RecordingService.start(
                    context = this,
                    serverProfileId = request.profileId,
                    uploadPolicyOverride = request.uploadPolicyOverride,
                    transcriptionPolicyOverride = request.transcriptionPolicyOverride,
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val voiceAssetApplication = application as VoiceAssetApplication
        recordingPlayback =
            createRecordingPlaybackController(
                context = this,
                recordings = voiceAssetApplication.container.recordings,
                scope = lifecycleScope,
            )
        val recordingExporter =
            RecordingExporter(
                context = this,
                recordings = voiceAssetApplication.container.recordings,
            )
        val authenticator = ApiServerProfileAuthenticator(voiceAssetApplication::createApiClient)
        val pairingAuthenticator = ApiDevicePairingAuthenticator()
        val assetMetadataEditor =
            ApiAssetMetadataEditor(
                profiles = voiceAssetApplication.container.serverProfiles,
                sessions = voiceAssetApplication.serverSessions,
                incrementalSync = voiceAssetApplication.container.incrementalSync,
            )
        val mobileAdministration =
            ApiMobileAdministration(
                profiles = voiceAssetApplication.container.serverProfiles,
                sessions = voiceAssetApplication.serverSessions,
            )
        val personalDeviceSessions =
            ApiPersonalDeviceSessions(
                profiles = voiceAssetApplication.container.serverProfiles,
                sessions = voiceAssetApplication.serverSessions,
            )
        val mainViewModel =
            ViewModelProvider(
                this,
                MainViewModel.Factory(
                    profiles = voiceAssetApplication.container.serverProfiles,
                    activeProfile = voiceAssetApplication.container.activeServerProfile,
                    recordings = voiceAssetApplication.container.recordings,
                    syncTasks = voiceAssetApplication.container.syncTasks,
                    transcripts = voiceAssetApplication.container.transcripts,
                    incrementalSync = voiceAssetApplication.container.incrementalSync,
                    syncScheduler = voiceAssetApplication.container.syncScheduler,
                    credentials = voiceAssetApplication.container.credentials,
                    authenticator = authenticator,
                    pairingAuthenticator = pairingAuthenticator,
                    assetMetadataEditor = assetMetadataEditor,
                    personalDeviceSessions = personalDeviceSessions,
                    mobileAdministration = mobileAdministration,
                    remoteAssetSyncScheduler = voiceAssetApplication.container.incrementalSyncScheduler,
                ),
            )[MainViewModel::class.java]
        setContent {
            val uiState = mainViewModel.uiState.collectAsStateWithLifecycle().value
            val playbackState = recordingPlayback.state.collectAsStateWithLifecycle().value
            VoiceAssetApp(
                uiState = uiState,
                playbackState = playbackState,
                onServerNameChanged = mainViewModel::updateServerName,
                onServerUrlChanged = mainViewModel::updateServerUrl,
                onServerEmailChanged = mainViewModel::updateServerEmail,
                onServerPasswordChanged = mainViewModel::updateServerPassword,
                onPairingPayloadChanged = mainViewModel::updatePairingPayload,
                onCustomCaPemChanged = mainViewModel::updateCustomCaPem,
                onCertificateFingerprintChanged = mainViewModel::updateCertificateFingerprint,
                onUploadPolicyChanged = mainViewModel::updateUploadPolicy,
                onTranscriptionPolicyChanged = mainViewModel::updateTranscriptionPolicy,
                onRecordingUploadPolicyOverrideChanged = mainViewModel::updateRecordingUploadPolicyOverride,
                onRecordingTranscriptionPolicyOverrideChanged =
                    mainViewModel::updateRecordingTranscriptionPolicyOverride,
                onSaveServer = mainViewModel::saveServerProfile,
                onPairServer = mainViewModel::pairServerProfile,
                onServerSelected = mainViewModel::selectServerProfile,
                onOfflineLibrarySearchQueryChanged = mainViewModel::updateOfflineLibrarySearchQuery,
                onClearOfflineLibrarySearch = mainViewModel::clearOfflineLibrarySearch,
                onRefreshSyncedAssets = mainViewModel::refreshRemoteAssets,
                onSessionReconnectEmailChanged = mainViewModel::updateSessionReconnectEmail,
                onSessionReconnectPasswordChanged = mainViewModel::updateSessionReconnectPassword,
                onReconnectActiveServerProfile = mainViewModel::reconnectActiveServerProfile,
                onRefreshDeviceSessions = mainViewModel::refreshDeviceSessions,
                onRequestDeviceSessionRevocation = mainViewModel::requestDeviceSessionRevocation,
                onCancelDeviceSessionRevocation = mainViewModel::cancelDeviceSessionRevocation,
                onConfirmDeviceSessionRevocation = mainViewModel::confirmDeviceSessionRevocation,
                onRefreshMobileAdministration = mainViewModel::refreshMobileAdministration,
                onSetProviderProfileEnabled = mainViewModel::setMobileProviderProfileEnabled,
                onCheckProviderProfileHealth = mainViewModel::checkMobileProviderProfileHealth,
                onRetryAdministrationJob = mainViewModel::retryMobileAdministrationJob,
                onEditAssetMetadata = mainViewModel::startAssetMetadataEdit,
                onAssetMetadataTitleChanged = mainViewModel::updateAssetMetadataTitle,
                onAssetMetadataLanguageChanged = mainViewModel::updateAssetMetadataLanguage,
                onAssetMetadataCollectionChanged = mainViewModel::updateAssetMetadataCollectionId,
                onSaveAssetMetadata = mainViewModel::saveAssetMetadata,
                onReloadAssetMetadata = mainViewModel::reloadAssetMetadata,
                onCloseAssetMetadataEditor = mainViewModel::closeAssetMetadataEditor,
                onRetryRecordingSync = mainViewModel::retryRecordingSync,
                onStartRecordingUpload = mainViewModel::startRecordingUpload,
                onStartRecordingTranscription = mainViewModel::startRecordingTranscription,
                onPlayRecording = { value ->
                    runCatching { RecordingSessionId.parse(value) }
                        .getOrNull()
                        ?.let(recordingPlayback::play)
                },
                onPauseRecordingPlayback = recordingPlayback::pause,
                onStopRecordingPlayback = recordingPlayback::stop,
                onExportRecording = { value ->
                    val recordingSessionId = runCatching { RecordingSessionId.parse(value) }.getOrNull()
                    if (recordingSessionId == null) {
                        showExportUnavailable()
                    } else {
                        lifecycleScope.launch {
                            val shareIntent =
                                try {
                                    recordingExporter.createShareIntent(recordingSessionId)
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (_: Exception) {
                                    null
                                }
                            if (shareIntent == null) {
                                showExportUnavailable()
                            } else {
                                startActivity(
                                    Intent.createChooser(
                                        shareIntent,
                                        getString(R.string.export_recording_chooser),
                                    ),
                                )
                            }
                        }
                    }
                },
                onStartRecording = {
                    val state = mainViewModel.uiState.value
                    requestRecordingStart(
                        profileId = state.activeServerProfileId?.let(ServerProfileId::parse),
                        uploadPolicyOverride = state.recordingUploadPolicyOverride,
                        transcriptionPolicyOverride = state.recordingTranscriptionPolicyOverride,
                    )
                },
                onPauseRecording = { RecordingService.pause(this) },
                onResumeRecording = { RecordingService.resume(this) },
                onStopRecording = { RecordingService.stop(this) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!noisyReceiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(noisyReceiver, filter)
            }
            noisyReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (::recordingPlayback.isInitialized) {
            recordingPlayback.pause()
        }
        if (noisyReceiverRegistered) {
            unregisterReceiver(noisyReceiver)
            noisyReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::recordingPlayback.isInitialized) {
            recordingPlayback.close()
        }
        super.onDestroy()
    }

    private fun requestRecordingStart(
        profileId: ServerProfileId?,
        uploadPolicyOverride: UploadPolicy?,
        transcriptionPolicyOverride: TranscriptionPolicy?,
    ) {
        val request =
            PendingRecordingRequest(
                profileId = profileId,
                uploadPolicyOverride = uploadPolicyOverride,
                transcriptionPolicyOverride = transcriptionPolicyOverride,
            )
        val missingPermissions =
            buildList {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        if (missingPermissions.isEmpty()) {
            RecordingService.start(
                context = this,
                serverProfileId = request.profileId,
                uploadPolicyOverride = request.uploadPolicyOverride,
                transcriptionPolicyOverride = request.transcriptionPolicyOverride,
            )
        } else {
            pendingRecordingRequest = request
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private data class PendingRecordingRequest(
        val profileId: ServerProfileId?,
        val uploadPolicyOverride: UploadPolicy?,
        val transcriptionPolicyOverride: TranscriptionPolicy?,
    )

    private fun showExportUnavailable() {
        Toast.makeText(this, R.string.export_recording_unavailable, Toast.LENGTH_SHORT).show()
    }
}
