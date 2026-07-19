package com.voiceasset.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.voiceasset.android.playback.RecordingPlaybackDecoderMode
import com.voiceasset.android.playback.RecordingPlaybackStatus
import com.voiceasset.android.playback.RecordingPlaybackUiState
import com.voiceasset.android.ui.theme.VoiceAssetTheme
import com.voiceasset.core.api.ProviderHealthStatus
import com.voiceasset.core.api.ProviderProfileState
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val OFFLINE_LIBRARY_SEARCH_TEST_TAG = "offline-library-search"
internal const val ASSET_METADATA_TITLE_TEST_TAG = "asset-metadata-title"
internal const val ASSET_METADATA_LANGUAGE_TEST_TAG = "asset-metadata-language"
internal const val ASSET_METADATA_COLLECTION_TEST_TAG = "asset-metadata-collection"
internal const val REFRESH_SYNCED_ASSETS_TEST_TAG = "refresh-synced-assets"
internal const val SESSION_RECONNECT_EMAIL_TEST_TAG = "session-reconnect-email"
internal const val SESSION_RECONNECT_PASSWORD_TEST_TAG = "session-reconnect-password"
internal const val SESSION_RECONNECT_SUBMIT_TEST_TAG = "session-reconnect-submit"
internal const val REFRESH_DEVICE_SESSIONS_TEST_TAG = "refresh-device-sessions"
internal const val DEVICE_SESSION_REVOKE_TEST_TAG_PREFIX = "device-session-revoke-"
internal const val CONFIRM_DEVICE_SESSION_REVOKE_TEST_TAG = "confirm-device-session-revoke"
internal const val CANCEL_DEVICE_SESSION_REVOKE_TEST_TAG = "cancel-device-session-revoke"
internal const val REFRESH_MOBILE_ADMINISTRATION_TEST_TAG = "refresh-mobile-administration"
internal const val PROVIDER_PROFILE_ACTION_TEST_TAG_PREFIX = "provider-profile-action-"
internal const val PROVIDER_PROFILE_HEALTH_TEST_TAG_PREFIX = "provider-profile-health-"
internal const val ADMINISTRATION_JOB_RETRY_TEST_TAG_PREFIX = "administration-job-retry-"
internal const val PAIRING_PAYLOAD_TEST_TAG = "pairing-payload"
internal const val SCAN_PAIRING_TEST_TAG = "scan-pairing"
internal const val PAIR_SERVER_TEST_TAG = "pair-server"
internal const val SERVER_NAME_TEST_TAG = "server-name"
internal const val SERVER_URL_TEST_TAG = "server-url"
internal const val SERVER_EMAIL_TEST_TAG = "server-email"
internal const val SERVER_PASSWORD_TEST_TAG = "server-password"
internal const val RECORD_BUTTON_TEST_TAG = "record-button"
internal const val RECORDER_WAVEFORM_TEST_TAG = "recorder-waveform"
internal const val LANGUAGE_SELECTOR_TEST_TAG = "language-selector"
internal const val LANGUAGE_CHINESE_TEST_TAG = "language-chinese"
internal const val RECORDER_SEARCH_TEST_TAG = "recorder-search"
internal const val RECORDER_SETTINGS_TEST_TAG = "recorder-settings"
internal const val RECORDER_BACK_TEST_TAG = "recorder-back"
internal const val RECORD_FAB_TEST_TAG = "record-fab"
internal const val RECORDING_ROW_TEST_TAG_PREFIX = "recording-row-"
private const val MAX_MOBILE_ADMINISTRATION_JOBS = 10
private val RECORDER_ACCENT_RED = Color(0xFFFF4B55)

private enum class RecorderSection {
    RECORD,
    RECORDINGS,
    SETTINGS,
}

private enum class RecordingFilter {
    ALL,
    NEEDS_ATTENTION,
    WITH_TRANSCRIPT,
}

private enum class RecordingSort {
    RECENT,
    NAME,
    DURATION,
}

private val RecorderSection.titleRes: Int
    get() =
        when (this) {
            RecorderSection.RECORD -> R.string.recorder_tab
            RecorderSection.RECORDINGS -> R.string.recordings_tab
            RecorderSection.SETTINGS -> R.string.settings_tab
        }

@Composable
fun VoiceAssetApp(
    uiState: AppUiState = initialAppUiState(),
    playbackState: RecordingPlaybackUiState = RecordingPlaybackUiState(),
    language: AppLanguage = AppLanguage.ENGLISH,
    onLanguageSelected: (AppLanguage) -> Unit = {},
    onServerNameChanged: (String) -> Unit = {},
    onServerUrlChanged: (String) -> Unit = {},
    onServerEmailChanged: (String) -> Unit = {},
    onServerPasswordChanged: (String) -> Unit = {},
    onPairingPayloadChanged: (String) -> Unit = {},
    onCustomCaPemChanged: (String) -> Unit = {},
    onCertificateFingerprintChanged: (String) -> Unit = {},
    onUploadPolicyChanged: (UploadPolicy) -> Unit = {},
    onTranscriptionPolicyChanged: (TranscriptionPolicy) -> Unit = {},
    onRecordingUploadPolicyOverrideChanged: (UploadPolicy?) -> Unit = {},
    onRecordingTranscriptionPolicyOverrideChanged: (TranscriptionPolicy?) -> Unit = {},
    onSaveServer: () -> Unit = {},
    onPairServer: () -> Unit = {},
    onScanPairingCode: () -> Unit = {},
    onServerSelected: (String) -> Unit = {},
    onOfflineLibrarySearchQueryChanged: (String) -> Unit = {},
    onClearOfflineLibrarySearch: () -> Unit = {},
    onRefreshSyncedAssets: () -> Unit = {},
    onSessionReconnectEmailChanged: (String) -> Unit = {},
    onSessionReconnectPasswordChanged: (String) -> Unit = {},
    onReconnectActiveServerProfile: () -> Unit = {},
    onRefreshDeviceSessions: () -> Unit = {},
    onRequestDeviceSessionRevocation: (String) -> Unit = {},
    onCancelDeviceSessionRevocation: () -> Unit = {},
    onConfirmDeviceSessionRevocation: () -> Unit = {},
    onRefreshMobileAdministration: () -> Unit = {},
    onSetProviderProfileEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onCheckProviderProfileHealth: (String) -> Unit = {},
    onRetryAdministrationJob: (String) -> Unit = {},
    onEditAssetMetadata: (String) -> Unit = {},
    onAssetMetadataTitleChanged: (String) -> Unit = {},
    onAssetMetadataLanguageChanged: (String) -> Unit = {},
    onAssetMetadataCollectionChanged: (String) -> Unit = {},
    onSaveAssetMetadata: () -> Unit = {},
    onReloadAssetMetadata: () -> Unit = {},
    onCloseAssetMetadataEditor: () -> Unit = {},
    onRetryRecordingSync: (String) -> Unit = {},
    onStartRecordingUpload: (String) -> Unit = {},
    onStartRecordingTranscription: (String) -> Unit = {},
    onPlayRecording: (String) -> Unit = {},
    onPauseRecordingPlayback: () -> Unit = {},
    onStopRecordingPlayback: () -> Unit = {},
    onPlaybackDecoderModeChanged: (RecordingPlaybackDecoderMode) -> Unit = {},
    onExportRecording: (String) -> Unit = {},
    onStartRecording: () -> Unit = {},
    onPauseRecording: () -> Unit = {},
    onResumeRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(RecorderSection.RECORDINGS) }
    var searchRequested by rememberSaveable { mutableStateOf(false) }
    val systemDarkTheme = isSystemInDarkTheme()
    var darkTheme by rememberSaveable { mutableStateOf(systemDarkTheme) }
    var recordingFormat by rememberSaveable { mutableStateOf("M4A") }
    var recordingSampleRate by rememberSaveable { mutableStateOf("44.1 kHz") }
    var recordingBitrate by rememberSaveable { mutableStateOf("256 kbps") }
    var recordingChannels by rememberSaveable { mutableStateOf("Mono") }
    var autoStartRecording by rememberSaveable { mutableStateOf(false) }
    var showRecordingNotification by rememberSaveable { mutableStateOf(true) }
    val startRecordingDescription = stringResource(R.string.start_recording)

    VoiceAssetTheme(darkTheme = darkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    RecorderAppBar(
                        section = section,
                        onBack = { section = RecorderSection.RECORDINGS },
                        onSearch = {
                            section = RecorderSection.RECORDINGS
                            searchRequested = true
                        },
                        onOpenSettings = { section = RecorderSection.SETTINGS },
                    )
                },
                floatingActionButton = {
                    if (section == RecorderSection.RECORDINGS) {
                        FloatingActionButton(
                            modifier =
                                Modifier
                                    .size(88.dp)
                                    .testTag(RECORD_FAB_TEST_TAG)
                                    .semantics {
                                        contentDescription = startRecordingDescription
                                    },
                            onClick = {
                                section = RecorderSection.RECORD
                                onStartRecording()
                            },
                            shape = CircleShape,
                            containerColor = RECORDER_ACCENT_RED,
                            contentColor = Color.White,
                        ) {
                            Text("●", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
            ) { contentPadding ->
                VoiceAssetHomeScreen(
                    uiState = uiState,
                    playbackState = playbackState,
                    onPlaybackDecoderModeChanged = onPlaybackDecoderModeChanged,
                    language = language,
                    onLanguageSelected = onLanguageSelected,
                    darkTheme = darkTheme,
                    onDarkThemeChanged = { darkTheme = it },
                    recordingFormat = recordingFormat,
                    onRecordingFormatChanged = { recordingFormat = it },
                    recordingSampleRate = recordingSampleRate,
                    onRecordingSampleRateChanged = { recordingSampleRate = it },
                    recordingBitrate = recordingBitrate,
                    onRecordingBitrateChanged = { recordingBitrate = it },
                    recordingChannels = recordingChannels,
                    onRecordingChannelsChanged = { recordingChannels = it },
                    autoStartRecording = autoStartRecording,
                    onAutoStartRecordingChanged = { autoStartRecording = it },
                    showRecordingNotification = showRecordingNotification,
                    onShowRecordingNotificationChanged = { showRecordingNotification = it },
                    section = section,
                    searchRequested = searchRequested,
                    onSearchRequestedChange = { searchRequested = it },
                    contentPadding = contentPadding,
                    onServerNameChanged = onServerNameChanged,
                    onServerUrlChanged = onServerUrlChanged,
                    onServerEmailChanged = onServerEmailChanged,
                    onServerPasswordChanged = onServerPasswordChanged,
                    onPairingPayloadChanged = onPairingPayloadChanged,
                    onCustomCaPemChanged = onCustomCaPemChanged,
                    onCertificateFingerprintChanged = onCertificateFingerprintChanged,
                    onUploadPolicyChanged = onUploadPolicyChanged,
                    onTranscriptionPolicyChanged = onTranscriptionPolicyChanged,
                    onRecordingUploadPolicyOverrideChanged = onRecordingUploadPolicyOverrideChanged,
                    onRecordingTranscriptionPolicyOverrideChanged =
                    onRecordingTranscriptionPolicyOverrideChanged,
                    onSaveServer = onSaveServer,
                    onPairServer = onPairServer,
                    onScanPairingCode = onScanPairingCode,
                    onServerSelected = onServerSelected,
                    onOfflineLibrarySearchQueryChanged = onOfflineLibrarySearchQueryChanged,
                    onClearOfflineLibrarySearch = onClearOfflineLibrarySearch,
                    onRefreshSyncedAssets = onRefreshSyncedAssets,
                    onSessionReconnectEmailChanged = onSessionReconnectEmailChanged,
                    onSessionReconnectPasswordChanged = onSessionReconnectPasswordChanged,
                    onReconnectActiveServerProfile = onReconnectActiveServerProfile,
                    onRefreshDeviceSessions = onRefreshDeviceSessions,
                    onRequestDeviceSessionRevocation = onRequestDeviceSessionRevocation,
                    onCancelDeviceSessionRevocation = onCancelDeviceSessionRevocation,
                    onConfirmDeviceSessionRevocation = onConfirmDeviceSessionRevocation,
                    onRefreshMobileAdministration = onRefreshMobileAdministration,
                    onSetProviderProfileEnabled = onSetProviderProfileEnabled,
                    onCheckProviderProfileHealth = onCheckProviderProfileHealth,
                    onRetryAdministrationJob = onRetryAdministrationJob,
                    onEditAssetMetadata = onEditAssetMetadata,
                    onAssetMetadataTitleChanged = onAssetMetadataTitleChanged,
                    onAssetMetadataLanguageChanged = onAssetMetadataLanguageChanged,
                    onAssetMetadataCollectionChanged = onAssetMetadataCollectionChanged,
                    onSaveAssetMetadata = onSaveAssetMetadata,
                    onReloadAssetMetadata = onReloadAssetMetadata,
                    onCloseAssetMetadataEditor = onCloseAssetMetadataEditor,
                    onRetryRecordingSync = onRetryRecordingSync,
                    onStartRecordingUpload = onStartRecordingUpload,
                    onStartRecordingTranscription = onStartRecordingTranscription,
                    onPlayRecording = onPlayRecording,
                    onPauseRecordingPlayback = onPauseRecordingPlayback,
                    onStopRecordingPlayback = onStopRecordingPlayback,
                    onExportRecording = onExportRecording,
                    onStartRecording = onStartRecording,
                    onPauseRecording = onPauseRecording,
                    onResumeRecording = onResumeRecording,
                    onStopRecording = onStopRecording,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecorderAppBar(
    section: RecorderSection,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val backDescription = stringResource(R.string.recorder_back_description)
    val searchDescription = stringResource(R.string.search_recordings_description)
    val settingsDescription = stringResource(R.string.open_settings_description)

    TopAppBar(
        navigationIcon = {
            if (section != RecorderSection.RECORDINGS) {
                IconButton(
                    modifier =
                        Modifier
                            .testTag(RECORDER_BACK_TEST_TAG)
                            .semantics { contentDescription = backDescription },
                    onClick = onBack,
                ) {
                    Text("‹", style = MaterialTheme.typography.headlineMedium)
                }
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = stringResource(section.titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            if (section == RecorderSection.RECORDINGS) {
                IconButton(
                    modifier =
                        Modifier
                            .testTag(RECORDER_SEARCH_TEST_TAG)
                            .semantics {
                                contentDescription = searchDescription
                            },
                    onClick = onSearch,
                ) {
                    Text("⌕", style = MaterialTheme.typography.titleLarge)
                }
            }
            if (section != RecorderSection.SETTINGS) {
                IconButton(
                    modifier =
                        Modifier
                            .testTag(RECORDER_SETTINGS_TEST_TAG)
                            .semantics { contentDescription = settingsDescription },
                    onClick = onOpenSettings,
                ) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
        },
    )
}

@Composable
private fun LanguageSelector(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    compact: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(
            modifier = Modifier.testTag(LANGUAGE_SELECTOR_TEST_TAG),
            onClick = { expanded = true },
        ) {
            Text(
                text =
                    if (compact) {
                        if (language == AppLanguage.SIMPLIFIED_CHINESE) "中" else "EN"
                    } else {
                        stringResource(
                            R.string.language_current,
                            stringResource(
                                if (language == AppLanguage.SIMPLIFIED_CHINESE) {
                                    R.string.language_chinese
                                } else {
                                    R.string.language_english
                                },
                            ),
                        )
                    },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.language_english)) },
                onClick = {
                    expanded = false
                    onLanguageSelected(AppLanguage.ENGLISH)
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag(LANGUAGE_CHINESE_TEST_TAG),
                text = { Text(stringResource(R.string.language_chinese)) },
                onClick = {
                    expanded = false
                    onLanguageSelected(AppLanguage.SIMPLIFIED_CHINESE)
                },
            )
        }
    }
}

@Composable
private fun VoiceAssetHomeScreen(
    uiState: AppUiState,
    playbackState: RecordingPlaybackUiState,
    contentPadding: PaddingValues,
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    recordingFormat: String,
    onRecordingFormatChanged: (String) -> Unit,
    recordingSampleRate: String,
    onRecordingSampleRateChanged: (String) -> Unit,
    recordingBitrate: String,
    onRecordingBitrateChanged: (String) -> Unit,
    recordingChannels: String,
    onRecordingChannelsChanged: (String) -> Unit,
    autoStartRecording: Boolean,
    onAutoStartRecordingChanged: (Boolean) -> Unit,
    showRecordingNotification: Boolean,
    onShowRecordingNotificationChanged: (Boolean) -> Unit,
    section: RecorderSection,
    searchRequested: Boolean,
    onSearchRequestedChange: (Boolean) -> Unit,
    onServerNameChanged: (String) -> Unit,
    onServerUrlChanged: (String) -> Unit,
    onServerEmailChanged: (String) -> Unit,
    onServerPasswordChanged: (String) -> Unit,
    onPairingPayloadChanged: (String) -> Unit,
    onCustomCaPemChanged: (String) -> Unit,
    onCertificateFingerprintChanged: (String) -> Unit,
    onUploadPolicyChanged: (UploadPolicy) -> Unit,
    onTranscriptionPolicyChanged: (TranscriptionPolicy) -> Unit,
    onRecordingUploadPolicyOverrideChanged: (UploadPolicy?) -> Unit,
    onRecordingTranscriptionPolicyOverrideChanged: (TranscriptionPolicy?) -> Unit,
    onSaveServer: () -> Unit,
    onPairServer: () -> Unit,
    onScanPairingCode: () -> Unit,
    onServerSelected: (String) -> Unit,
    onOfflineLibrarySearchQueryChanged: (String) -> Unit,
    onClearOfflineLibrarySearch: () -> Unit,
    onRefreshSyncedAssets: () -> Unit,
    onSessionReconnectEmailChanged: (String) -> Unit,
    onSessionReconnectPasswordChanged: (String) -> Unit,
    onReconnectActiveServerProfile: () -> Unit,
    onRefreshDeviceSessions: () -> Unit,
    onRequestDeviceSessionRevocation: (String) -> Unit,
    onCancelDeviceSessionRevocation: () -> Unit,
    onConfirmDeviceSessionRevocation: () -> Unit,
    onRefreshMobileAdministration: () -> Unit,
    onSetProviderProfileEnabled: (String, Boolean) -> Unit,
    onCheckProviderProfileHealth: (String) -> Unit,
    onRetryAdministrationJob: (String) -> Unit,
    onEditAssetMetadata: (String) -> Unit,
    onAssetMetadataTitleChanged: (String) -> Unit,
    onAssetMetadataLanguageChanged: (String) -> Unit,
    onAssetMetadataCollectionChanged: (String) -> Unit,
    onSaveAssetMetadata: () -> Unit,
    onReloadAssetMetadata: () -> Unit,
    onCloseAssetMetadataEditor: () -> Unit,
    onRetryRecordingSync: (String) -> Unit,
    onStartRecordingUpload: (String) -> Unit,
    onStartRecordingTranscription: (String) -> Unit,
    onPlayRecording: (String) -> Unit,
    onPauseRecordingPlayback: () -> Unit,
    onStopRecordingPlayback: () -> Unit,
    onPlaybackDecoderModeChanged: (RecordingPlaybackDecoderMode) -> Unit,
    onExportRecording: (String) -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(section) { scrollState.scrollTo(0) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (section != RecorderSection.RECORD) {
            Text(
                text =
                    stringResource(
                        if (section == RecorderSection.RECORDINGS) {
                            R.string.recordings_tab_description
                        } else {
                            R.string.settings_tab_description
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (section == RecorderSection.SETTINGS) {
            RecorderSettingsCard(
                language = language,
                onLanguageSelected = onLanguageSelected,
                playbackDecoderMode = playbackState.decoderMode,
                onPlaybackDecoderModeChanged = onPlaybackDecoderModeChanged,
                darkTheme = darkTheme,
                onDarkThemeChanged = onDarkThemeChanged,
                recordingFormat = recordingFormat,
                onRecordingFormatChanged = onRecordingFormatChanged,
                recordingSampleRate = recordingSampleRate,
                onRecordingSampleRateChanged = onRecordingSampleRateChanged,
                recordingBitrate = recordingBitrate,
                onRecordingBitrateChanged = onRecordingBitrateChanged,
                recordingChannels = recordingChannels,
                onRecordingChannelsChanged = onRecordingChannelsChanged,
                autoStartRecording = autoStartRecording,
                onAutoStartRecordingChanged = onAutoStartRecordingChanged,
                showRecordingNotification = showRecordingNotification,
                onShowRecordingNotificationChanged = onShowRecordingNotificationChanged,
            )
        }

        if (section == RecorderSection.RECORD) {
            Text(
                text = stringResource(R.string.initialized),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.initialized_description),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            RecordingCard(
                status = uiState.recordingStatus,
                error = uiState.recordingError,
                uploadPolicyOverride = uiState.recordingUploadPolicyOverride,
                transcriptionPolicyOverride = uiState.recordingTranscriptionPolicyOverride,
                onUploadPolicyOverrideChanged = onRecordingUploadPolicyOverrideChanged,
                onTranscriptionPolicyOverrideChanged = onRecordingTranscriptionPolicyOverrideChanged,
                onStart = onStartRecording,
                onPause = onPauseRecording,
                onResume = onResumeRecording,
                onStop = onStopRecording,
            )
        }

        if (section == RecorderSection.RECORDINGS) {
            OfflineLibrarySearchField(
                query = uiState.offlineLibrarySearchQuery,
                onQueryChanged = onOfflineLibrarySearchQueryChanged,
                onClear = onClearOfflineLibrarySearch,
                compact = true,
            )
        }

        if (section != RecorderSection.SETTINGS) {
            LocalRecordingsCard(
                totalCount = uiState.localRecordingCount,
                matchCount = uiState.localRecordingMatchCount,
                recordings = uiState.localRecordings,
                isSearchActive = uiState.offlineLibrarySearchQuery.isNotBlank(),
                playbackState = playbackState,
                onRetry = onRetryRecordingSync,
                onStartUpload = onStartRecordingUpload,
                onStartTranscription = onStartRecordingTranscription,
                onPlay = onPlayRecording,
                onPausePlayback = onPauseRecordingPlayback,
                onStopPlayback = onStopRecordingPlayback,
                onExport = onExportRecording,
            )
        }

        if (section == RecorderSection.RECORD) {
            TranscriptCard(
                recordingStatus = uiState.recordingStatus,
                transcriptionPolicy = uiState.activeRecordingTranscriptionPolicy,
                language = uiState.transcriptLanguage,
                text = uiState.transcriptText,
            )
        }

        if (section == RecorderSection.RECORD) {
            OfflineLibrarySearchField(
                query = uiState.offlineLibrarySearchQuery,
                onQueryChanged = onOfflineLibrarySearchQueryChanged,
                onClear = onClearOfflineLibrarySearch,
            )
        }

        if (section == RecorderSection.RECORDINGS && searchRequested) {
            TextButton(onClick = { onSearchRequestedChange(false) }) {
                Text(stringResource(R.string.close_search))
            }
        }

        if (section != RecorderSection.RECORDINGS) {
            StatusCard(
                label = stringResource(R.string.server_status),
                value =
                    when (uiState.serverStatus) {
                        ServerStatus.NOT_CONFIGURED -> stringResource(R.string.server_not_configured)
                        ServerStatus.CONFIGURED -> stringResource(R.string.server_configured)
                    },
                supportingText =
                    when (uiState.serverStatus) {
                        ServerStatus.NOT_CONFIGURED -> stringResource(R.string.server_not_configured_description)
                        ServerStatus.CONFIGURED ->
                            pluralStringResource(
                                R.plurals.server_configured_description,
                                uiState.serverProfiles.size,
                                uiState.serverProfiles.size,
                            )
                    },
            )

            uiState.serverProfiles.forEach { profile ->
                ServerProfileCard(
                    profile = profile,
                    canSwitch = uiState.recordingStatus.allowsProfileSwitch(),
                    onSelect = onServerSelected,
                )
            }
        }

        if (uiState.serverStatus == ServerStatus.CONFIGURED) {
            if (section != RecorderSection.RECORDINGS) {
                ServerSessionReconnectCard(
                    state = uiState.serverSessionReconnect,
                    onEmailChanged = onSessionReconnectEmailChanged,
                    onPasswordChanged = onSessionReconnectPasswordChanged,
                    onReconnect = onReconnectActiveServerProfile,
                )
                DeviceSessionsCard(
                    state = uiState.deviceSessions,
                    onRefresh = onRefreshDeviceSessions,
                    onRequestRevocation = onRequestDeviceSessionRevocation,
                    onCancelRevocation = onCancelDeviceSessionRevocation,
                    onConfirmRevocation = onConfirmDeviceSessionRevocation,
                )
                MobileAdministrationCard(
                    state = uiState.mobileAdministration,
                    onRefresh = onRefreshMobileAdministration,
                    onSetProviderProfileEnabled = onSetProviderProfileEnabled,
                    onCheckProviderProfileHealth = onCheckProviderProfileHealth,
                    onRetryAdministrationJob = onRetryAdministrationJob,
                )
            }
            SyncedAssetsCard(
                totalCount = uiState.syncedAssetCount,
                matchCount = uiState.syncedAssetMatchCount,
                assets = uiState.syncedAssets,
                isSearchActive = uiState.offlineLibrarySearchQuery.isNotBlank(),
                onRefresh = onRefreshSyncedAssets,
                onEdit = onEditAssetMetadata,
            )
            uiState.assetMetadataEditor?.let { assetEditor ->
                AssetMetadataEditorCard(
                    editor = assetEditor,
                    onTitleChanged = onAssetMetadataTitleChanged,
                    onLanguageChanged = onAssetMetadataLanguageChanged,
                    onCollectionChanged = onAssetMetadataCollectionChanged,
                    onSave = onSaveAssetMetadata,
                    onReload = onReloadAssetMetadata,
                    onClose = onCloseAssetMetadataEditor,
                )
            }
        }

        if (section != RecorderSection.RECORDINGS) {
            ServerProfileForm(
                draft = uiState.serverDraft,
                isSaving = uiState.isSavingServer,
                error = uiState.serverFormError,
                onNameChanged = onServerNameChanged,
                onUrlChanged = onServerUrlChanged,
                onEmailChanged = onServerEmailChanged,
                onPasswordChanged = onServerPasswordChanged,
                onPairingPayloadChanged = onPairingPayloadChanged,
                onCustomCaPemChanged = onCustomCaPemChanged,
                onFingerprintChanged = onCertificateFingerprintChanged,
                onUploadPolicyChanged = onUploadPolicyChanged,
                onTranscriptionPolicyChanged = onTranscriptionPolicyChanged,
                onSave = onSaveServer,
                onPair = onPairServer,
                onScan = onScanPairingCode,
            )
        }
    }
}

@Composable
private fun RecorderSettingsCard(
    language: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    playbackDecoderMode: RecordingPlaybackDecoderMode,
    onPlaybackDecoderModeChanged: (RecordingPlaybackDecoderMode) -> Unit,
    darkTheme: Boolean,
    onDarkThemeChanged: (Boolean) -> Unit,
    recordingFormat: String,
    onRecordingFormatChanged: (String) -> Unit,
    recordingSampleRate: String,
    onRecordingSampleRateChanged: (String) -> Unit,
    recordingBitrate: String,
    onRecordingBitrateChanged: (String) -> Unit,
    recordingChannels: String,
    onRecordingChannelsChanged: (String) -> Unit,
    autoStartRecording: Boolean,
    onAutoStartRecordingChanged: (Boolean) -> Unit,
    showRecordingNotification: Boolean,
    onShowRecordingNotificationChanged: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.recorder_settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.recorder_settings_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.language_setting), style = MaterialTheme.typography.bodyLarge)
                LanguageSelector(
                    language = language,
                    onLanguageSelected = onLanguageSelected,
                )
            }
            SettingChoiceRow(
                label = stringResource(R.string.recording_format),
                values = listOf("M4A", "WAV", "3GP"),
                selected = recordingFormat,
                onSelected = onRecordingFormatChanged,
                tagPrefix = "recording-format-",
            )
            SettingChoiceRow(
                label = stringResource(R.string.recording_sample_rate),
                values = listOf("16 kHz", "44.1 kHz", "48 kHz"),
                selected = recordingSampleRate,
                onSelected = onRecordingSampleRateChanged,
                tagPrefix = "recording-sample-rate-",
            )
            SettingChoiceRow(
                label = stringResource(R.string.recording_bitrate),
                values = listOf("128 kbps", "256 kbps", "320 kbps"),
                selected = recordingBitrate,
                onSelected = onRecordingBitrateChanged,
                tagPrefix = "recording-bitrate-",
            )
            SettingChoiceRow(
                label = stringResource(R.string.recording_channels),
                values = listOf("Mono", "Stereo"),
                selected = recordingChannels,
                onSelected = onRecordingChannelsChanged,
                tagPrefix = "recording-channels-",
            )
            PlaybackDecoderChoiceRow(
                selected = playbackDecoderMode,
                onSelected = onPlaybackDecoderModeChanged,
            )
            SettingSwitchRow(
                label = stringResource(R.string.dark_theme),
                checked = darkTheme,
                onCheckedChange = onDarkThemeChanged,
            )
            SettingSwitchRow(
                label = stringResource(R.string.auto_start_recording),
                checked = autoStartRecording,
                onCheckedChange = onAutoStartRecordingChanged,
            )
            SettingSwitchRow(
                label = stringResource(R.string.recording_notification),
                checked = showRecordingNotification,
                onCheckedChange = onShowRecordingNotificationChanged,
            )
        }
    }
}

@Composable
private fun PlaybackDecoderChoiceRow(
    selected: RecordingPlaybackDecoderMode,
    onSelected: (RecordingPlaybackDecoderMode) -> Unit,
) {
    val values =
        listOf(
            RecordingPlaybackDecoderMode.SYSTEM_DEFAULT to stringResource(R.string.playback_decoder_system),
            RecordingPlaybackDecoderMode.HARDWARE_PREFERRED to stringResource(R.string.playback_decoder_hardware),
            RecordingPlaybackDecoderMode.COMPATIBILITY to stringResource(R.string.playback_decoder_compatibility),
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.playback_decoder), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(R.string.playback_decoder_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            values.forEach { (mode, label) ->
                val modifier = Modifier.weight(1f).testTag("playback-decoder-${mode.name.lowercase()}")
                if (mode == selected) {
                    Button(modifier = modifier, onClick = { onSelected(mode) }) { Text(label) }
                } else {
                    OutlinedButton(modifier = modifier, onClick = { onSelected(mode) }) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun SettingChoiceRow(
    label: String,
    values: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    tagPrefix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            values.forEach { value ->
                val modifier = Modifier.weight(1f).testTag(tagPrefix + value)
                if (value == selected) {
                    Button(modifier = modifier, onClick = { onSelected(value) }) {
                        Text(value)
                    }
                } else {
                    OutlinedButton(modifier = modifier, onClick = { onSelected(value) }) {
                        Text(value)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ServerSessionReconnectCard(
    state: ServerSessionReconnectUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onReconnect: () -> Unit,
) {
    val submitting = state.status == ServerSessionReconnectStatus.SUBMITTING
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.session_reconnect_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.session_reconnect_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.email,
                onValueChange = onEmailChanged,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(SESSION_RECONNECT_EMAIL_TEST_TAG),
                label = { Text(stringResource(R.string.session_reconnect_email)) },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                singleLine = true,
                enabled = !submitting,
            )
            OutlinedTextField(
                value = state.password.value,
                onValueChange = onPasswordChanged,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(SESSION_RECONNECT_PASSWORD_TEST_TAG),
                label = { Text(stringResource(R.string.session_reconnect_password)) },
                supportingText = { Text(stringResource(R.string.session_reconnect_password_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                singleLine = true,
                enabled = !submitting,
            )
            state.error?.let { error ->
                Text(
                    text = sessionReconnectErrorText(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.status == ServerSessionReconnectStatus.SUCCEEDED) {
                Text(
                    text = stringResource(R.string.session_reconnect_succeeded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                modifier = Modifier.testTag(SESSION_RECONNECT_SUBMIT_TEST_TAG),
                onClick = onReconnect,
                enabled = !submitting && state.email.isNotBlank() && state.password.value.isNotEmpty(),
            ) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text(stringResource(R.string.session_reconnect_submit))
                }
            }
        }
    }
}

@Composable
private fun DeviceSessionsCard(
    state: DeviceSessionsUiState,
    onRefresh: () -> Unit,
    onRequestRevocation: (String) -> Unit,
    onCancelRevocation: () -> Unit,
    onConfirmRevocation: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.device_sessions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.device_sessions_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                modifier = Modifier.testTag(REFRESH_DEVICE_SESSIONS_TEST_TAG),
                onClick = onRefresh,
                enabled =
                    state.status != DeviceSessionsStatus.LOADING &&
                        state.pendingRevocationId == null &&
                        state.revokingId == null,
            ) {
                Text(stringResource(R.string.refresh_device_sessions))
            }
            if (state.status == DeviceSessionsStatus.LOADING) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.device_sessions_loading))
                }
            }
            state.error?.let { error ->
                Text(
                    text = deviceSessionsErrorText(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.status == DeviceSessionsStatus.IDLE) {
                Text(
                    text = stringResource(R.string.device_sessions_not_loaded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.status == DeviceSessionsStatus.READY && state.items.isEmpty()) {
                Text(stringResource(R.string.device_sessions_empty))
            }
            state.items.forEach { session ->
                val pending = state.pendingRevocationId == session.id
                val revoking = state.revokingId == session.id
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(session.deviceName, fontWeight = FontWeight.Medium)
                        if (session.current) {
                            Text(
                                text = stringResource(R.string.device_session_current),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.device_session_identifier, session.id),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.device_session_activity,
                                session.lastSeenAt,
                                session.refreshExpiresAt,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pending) {
                        Text(
                            text = stringResource(R.string.device_session_revoke_confirmation, session.deviceName),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text =
                                stringResource(
                                    if (session.current) {
                                        R.string.device_session_revoke_current_warning
                                    } else {
                                        R.string.device_session_revoke_remote_warning
                                    },
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                modifier = Modifier.testTag(CANCEL_DEVICE_SESSION_REVOKE_TEST_TAG),
                                onClick = onCancelRevocation,
                            ) {
                                Text(stringResource(R.string.cancel_device_session_revoke))
                            }
                            Button(
                                modifier = Modifier.testTag(CONFIRM_DEVICE_SESSION_REVOKE_TEST_TAG),
                                onClick = onConfirmRevocation,
                            ) {
                                Text(stringResource(R.string.confirm_device_session_revoke))
                            }
                        }
                    } else {
                        OutlinedButton(
                            modifier = Modifier.testTag(DEVICE_SESSION_REVOKE_TEST_TAG_PREFIX + session.id),
                            onClick = { onRequestRevocation(session.id) },
                            enabled =
                                state.pendingRevocationId == null &&
                                    state.revokingId == null,
                        ) {
                            if (revoking) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            } else {
                                Text(
                                    stringResource(
                                        if (session.current) {
                                            R.string.sign_out_this_device
                                        } else {
                                            R.string.revoke_device_session
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileAdministrationCard(
    state: MobileAdministrationUiState,
    onRefresh: () -> Unit,
    onSetProviderProfileEnabled: (String, Boolean) -> Unit,
    onCheckProviderProfileHealth: (String) -> Unit,
    onRetryAdministrationJob: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.mobile_administration_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.mobile_administration_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                modifier = Modifier.testTag(REFRESH_MOBILE_ADMINISTRATION_TEST_TAG),
                onClick = onRefresh,
                enabled =
                    state.status != MobileAdministrationStatus.LOADING &&
                        state.busyProviderProfileId == null &&
                        state.busyJobId == null,
            ) {
                Text(stringResource(R.string.refresh_mobile_administration))
            }
            if (state.status == MobileAdministrationStatus.LOADING) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.mobile_administration_loading))
                }
            }
            state.error?.let { error ->
                Text(
                    text = mobileAdministrationErrorText(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.systemStatus?.let { system ->
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.mobile_administration_system_status),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(
                        R.string.mobile_administration_asset_summary,
                        system.assetCount,
                        system.storageObjectCount,
                        system.storageBytes,
                    ),
                )
                Text(
                    stringResource(
                        R.string.mobile_administration_transcript_summary,
                        system.transcriptCount,
                        system.revisionCount,
                        system.activeUsers,
                    ),
                )
                Text(
                    stringResource(
                        R.string.mobile_administration_job_summary,
                        system.jobCount,
                        system.queuedJobCount,
                        system.runningJobCount,
                        system.retryWaitJobCount,
                        system.failedJobCount,
                    ),
                )
                Text(
                    stringResource(
                        R.string.mobile_administration_provider_summary,
                        system.enabledAsrCount,
                        system.enabledLlmCount,
                    ),
                )
                Text(
                    text = stringResource(R.string.mobile_administration_generated_at, system.generatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.status == MobileAdministrationStatus.READY) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.mobile_administration_provider_profiles),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (state.providers.isEmpty()) {
                    Text(stringResource(R.string.mobile_administration_no_provider_profiles))
                } else {
                    state.providers.forEach { profile ->
                        val checkingHealth =
                            state.busyProviderProfileId == profile.id &&
                                state.busyProviderAction == MobileProviderAction.HEALTH_CHECK
                        val updatingState =
                            state.busyProviderProfileId == profile.id &&
                                state.busyProviderAction == MobileProviderAction.STATE_UPDATE
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(profile.displayName, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(
                                    R.string.mobile_administration_provider_profile_details,
                                    profile.family.name,
                                    profile.providerId,
                                    providerProfileStateLabel(profile.state),
                                    profile.priority,
                                    profile.version,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text =
                                    if (profile.secretConfigured) {
                                        stringResource(R.string.mobile_administration_secret_configured)
                                    } else {
                                        stringResource(R.string.mobile_administration_secret_not_configured)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = providerHealthLabel(profile),
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (profile.healthStatus == ProviderHealthStatus.UNHEALTHY) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            OutlinedButton(
                                modifier =
                                    Modifier.testTag(
                                        PROVIDER_PROFILE_HEALTH_TEST_TAG_PREFIX + profile.id,
                                    ),
                                onClick = { onCheckProviderProfileHealth(profile.id) },
                                enabled = state.busyProviderProfileId == null && state.busyJobId == null,
                            ) {
                                if (checkingHealth) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                } else {
                                    Text(stringResource(R.string.check_provider_profile_health))
                                }
                            }
                            OutlinedButton(
                                modifier =
                                    Modifier.testTag(
                                        PROVIDER_PROFILE_ACTION_TEST_TAG_PREFIX + profile.id,
                                    ),
                                onClick = {
                                    onSetProviderProfileEnabled(
                                        profile.id,
                                        profile.state != ProviderProfileState.ENABLED,
                                    )
                                },
                                enabled = state.busyProviderProfileId == null && state.busyJobId == null,
                            ) {
                                if (updatingState) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                } else {
                                    Text(
                                        if (profile.state == ProviderProfileState.ENABLED) {
                                            stringResource(R.string.disable_provider_profile)
                                        } else {
                                            stringResource(R.string.enable_provider_profile)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.mobile_administration_recent_jobs),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (state.jobs.isEmpty()) {
                    Text(stringResource(R.string.mobile_administration_no_jobs))
                } else {
                    state.jobs.take(MAX_MOBILE_ADMINISTRATION_JOBS).forEach { job ->
                        val retrying = state.busyJobId == job.id
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(job.kind, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(
                                    R.string.mobile_administration_job_details,
                                    job.state,
                                    job.attempts,
                                    job.maxAttempts,
                                    job.updatedAt,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            job.lastErrorCode?.let { errorCode ->
                                Text(
                                    stringResource(R.string.mobile_administration_job_error, errorCode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (job.retryable) {
                                OutlinedButton(
                                    modifier =
                                        Modifier.testTag(
                                            ADMINISTRATION_JOB_RETRY_TEST_TAG_PREFIX + job.id,
                                        ),
                                    onClick = { onRetryAdministrationJob(job.id) },
                                    enabled =
                                        state.busyJobId == null &&
                                            state.busyProviderProfileId == null,
                                ) {
                                    if (retrying) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                    } else {
                                        Text(stringResource(R.string.retry_administration_job))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun providerProfileStateLabel(state: ProviderProfileState): String =
    when (state) {
        ProviderProfileState.ENABLED -> stringResource(R.string.provider_profile_enabled)
        ProviderProfileState.DISABLED -> stringResource(R.string.provider_profile_disabled)
    }

@Composable
private fun providerHealthLabel(profile: MobileProviderProfileSummary): String =
    when (profile.healthStatus) {
        null -> stringResource(R.string.provider_profile_health_not_checked)
        ProviderHealthStatus.HEALTHY ->
            stringResource(
                R.string.provider_profile_health_healthy,
                profile.healthCheckedAt ?: stringResource(R.string.unknown_value),
            )
        ProviderHealthStatus.UNHEALTHY ->
            stringResource(
                R.string.provider_profile_health_unhealthy,
                profile.healthErrorClass?.name ?: stringResource(R.string.unknown_value),
                profile.healthCheckedAt ?: stringResource(R.string.unknown_value),
            )
    }

@Composable
private fun sessionReconnectErrorText(error: ServerSessionReconnectError): String =
    stringResource(
        when (error) {
            ServerSessionReconnectError.INVALID_EMAIL -> R.string.session_reconnect_invalid_email
            ServerSessionReconnectError.INVALID_PASSWORD -> R.string.session_reconnect_invalid_password
            ServerSessionReconnectError.AUTHENTICATION_FAILED -> R.string.session_reconnect_authentication_failed
            ServerSessionReconnectError.PROFILE_UNAVAILABLE -> R.string.session_reconnect_profile_unavailable
            ServerSessionReconnectError.CONNECTION_FAILED -> R.string.session_reconnect_connection_failed
            ServerSessionReconnectError.TLS_FAILED -> R.string.session_reconnect_tls_failed
            ServerSessionReconnectError.PROTOCOL_MISMATCH -> R.string.session_reconnect_protocol_mismatch
            ServerSessionReconnectError.SECURE_STORAGE_FAILED -> R.string.session_reconnect_secure_storage_failed
            ServerSessionReconnectError.RECONNECT_FAILED -> R.string.session_reconnect_failed
        },
    )

@Composable
private fun deviceSessionsErrorText(error: DeviceSessionsError): String =
    stringResource(
        when (error) {
            DeviceSessionsError.AUTHENTICATION_REQUIRED -> R.string.device_sessions_authentication_required
            DeviceSessionsError.PERMISSION_DENIED -> R.string.device_sessions_permission_denied
            DeviceSessionsError.PROFILE_UNAVAILABLE -> R.string.device_sessions_profile_unavailable
            DeviceSessionsError.NOT_FOUND -> R.string.device_sessions_not_found
            DeviceSessionsError.CONNECTION_FAILED -> R.string.device_sessions_connection_failed
            DeviceSessionsError.TLS_FAILED -> R.string.device_sessions_tls_failed
            DeviceSessionsError.PROTOCOL_MISMATCH -> R.string.device_sessions_protocol_mismatch
            DeviceSessionsError.SECURE_STORAGE_FAILED -> R.string.device_sessions_secure_storage_failed
            DeviceSessionsError.LOAD_FAILED -> R.string.device_sessions_load_failed
            DeviceSessionsError.REVOKE_FAILED -> R.string.device_sessions_revoke_failed
        },
    )

@Composable
private fun mobileAdministrationErrorText(error: MobileAdministrationError): String =
    stringResource(
        when (error) {
            MobileAdministrationError.AUTHENTICATION_REQUIRED -> R.string.mobile_administration_authentication_required
            MobileAdministrationError.PERMISSION_DENIED -> R.string.mobile_administration_permission_denied
            MobileAdministrationError.PROFILE_UNAVAILABLE -> R.string.mobile_administration_profile_unavailable
            MobileAdministrationError.CONFLICT -> R.string.mobile_administration_conflict
            MobileAdministrationError.CONNECTION_FAILED -> R.string.mobile_administration_connection_failed
            MobileAdministrationError.TLS_FAILED -> R.string.mobile_administration_tls_failed
            MobileAdministrationError.PROTOCOL_MISMATCH -> R.string.mobile_administration_protocol_mismatch
            MobileAdministrationError.SECURE_STORAGE_FAILED -> R.string.mobile_administration_secure_storage_failed
            MobileAdministrationError.LOAD_FAILED -> R.string.mobile_administration_load_failed
            MobileAdministrationError.UPDATE_FAILED -> R.string.mobile_administration_update_failed
            MobileAdministrationError.HEALTH_CHECK_FAILED -> R.string.mobile_administration_health_check_failed
            MobileAdministrationError.JOB_NOT_RETRYABLE -> R.string.mobile_administration_job_not_retryable
            MobileAdministrationError.JOB_RETRY_FAILED -> R.string.mobile_administration_job_retry_failed
        },
    )

@Composable
private fun OfflineLibrarySearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClear: () -> Unit,
    compact: Boolean = false,
) {
    if (compact) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(OFFLINE_LIBRARY_SEARCH_TEST_TAG),
            placeholder = { Text(stringResource(R.string.offline_library_search)) },
            leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.clear_search))
                    }
                }
            },
            shape = RoundedCornerShape(28.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(OFFLINE_LIBRARY_SEARCH_TEST_TAG),
                label = { Text(stringResource(R.string.offline_library_search)) },
                supportingText = { Text(stringResource(R.string.offline_library_search_supporting)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            if (query.isNotBlank()) {
                OutlinedButton(onClick = onClear) {
                    Text(stringResource(R.string.clear_search))
                }
            }
        }
    }
}

@Composable
private fun LocalRecordingsCard(
    totalCount: Int,
    matchCount: Int,
    recordings: List<LocalRecordingSummary>,
    isSearchActive: Boolean,
    playbackState: RecordingPlaybackUiState,
    onRetry: (String) -> Unit,
    onStartUpload: (String) -> Unit,
    onStartTranscription: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPausePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onExport: (String) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(RecordingFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(RecordingSort.RECENT) }
    var filterExpanded by rememberSaveable { mutableStateOf(false) }
    var sortExpanded by rememberSaveable { mutableStateOf(false) }
    val visibleRecordings =
        recordings
            .filter { recording ->
                when (filter) {
                    RecordingFilter.ALL -> true
                    RecordingFilter.NEEDS_ATTENTION ->
                        recording.errorCode != null ||
                            recording.syncStatus == SyncUiStatus.FAILED ||
                            recording.syncStatus == SyncUiStatus.BLOCKED
                    RecordingFilter.WITH_TRANSCRIPT -> recording.hasTranscript
                }
            }.let { filtered ->
                when (sort) {
                    RecordingSort.RECENT -> filtered
                    RecordingSort.NAME -> filtered.sortedBy { it.fileName.lowercase() }
                    RecordingSort.DURATION -> filtered.sortedByDescending { it.durationMillis ?: 0L }
                }
            }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.local_recordings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box {
                        TextButton(
                            modifier = Modifier.testTag("recording-filter"),
                            onClick = { filterExpanded = true },
                        ) {
                            Text(
                                text =
                                    stringResource(R.string.recording_filter) +
                                        ": " +
                                        recordingFilterLabel(filter),
                            )
                        }
                        DropdownMenu(
                            expanded = filterExpanded,
                            onDismissRequest = { filterExpanded = false },
                        ) {
                            RecordingFilter.values().forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(recordingFilterLabel(candidate)) },
                                    onClick = {
                                        filter = candidate
                                        filterExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(
                            modifier = Modifier.testTag("recording-sort"),
                            onClick = { sortExpanded = true },
                        ) {
                            Text(
                                text =
                                    stringResource(R.string.recording_sort) +
                                        ": " +
                                        recordingSortLabel(sort),
                            )
                        }
                        DropdownMenu(
                            expanded = sortExpanded,
                            onDismissRequest = { sortExpanded = false },
                        ) {
                            RecordingSort.values().forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(recordingSortLabel(candidate)) },
                                    onClick = {
                                        sort = candidate
                                        sortExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text =
                    if (isSearchActive) {
                        pluralStringResource(
                            R.plurals.local_recordings_search_description,
                            matchCount,
                            matchCount,
                            totalCount,
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.local_recordings_description,
                            totalCount,
                            totalCount,
                        )
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val hiddenPlaybackRecordingId =
                playbackState.recordingSessionId?.takeIf { recordingId ->
                    playbackState.status != RecordingPlaybackStatus.IDLE &&
                        recordings.none { recording -> recording.id == recordingId }
                }
            if (hiddenPlaybackRecordingId != null) {
                Text(
                    text = stringResource(R.string.filtered_recording_playback_controls),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RecordingPlaybackActions(
                    recordingId = hiddenPlaybackRecordingId,
                    playbackState = playbackState,
                    onPlay = onPlay,
                    onPause = onPausePlayback,
                    onStop = onStopPlayback,
                )
            }
            if (visibleRecordings.isEmpty()) {
                Text(
                    text =
                        stringResource(
                            if (isSearchActive && totalCount > 0) {
                                R.string.local_recordings_search_empty
                            } else {
                                R.string.local_recordings_empty
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visibleRecordings.forEachIndexed { index, recording ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    LocalRecordingRow(
                        recording = recording,
                        playbackState = playbackState,
                        onRetry = onRetry,
                        onStartUpload = onStartUpload,
                        onStartTranscription = onStartTranscription,
                        onPlay = onPlay,
                        onPausePlayback = onPausePlayback,
                        onStopPlayback = onStopPlayback,
                        onExport = onExport,
                    )
                }
                if (matchCount > visibleRecordings.size) {
                    Text(
                        text = stringResource(R.string.local_recordings_recent_limit, visibleRecordings.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalRecordingRow(
    recording: LocalRecordingSummary,
    playbackState: RecordingPlaybackUiState,
    onRetry: (String) -> Unit,
    onStartUpload: (String) -> Unit,
    onStartTranscription: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPausePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onExport: (String) -> Unit,
) {
    val isCurrentPlayback = playbackState.recordingSessionId == recording.id
    val playbackStatus =
        if (isCurrentPlayback) playbackState.status else RecordingPlaybackStatus.IDLE
    val playDescription =
        stringResource(
            when (playbackStatus) {
                RecordingPlaybackStatus.PLAYING -> R.string.pause_playback
                RecordingPlaybackStatus.PREPARING,
                RecordingPlaybackStatus.VERIFYING,
                -> R.string.stop_playback
                else -> R.string.play_recording
            },
        )

    Column(
        modifier =
            Modifier
                .testTag(RECORDING_ROW_TEST_TAG_PREFIX + recording.id)
                .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                modifier =
                    Modifier
                        .size(56.dp)
                        .testTag("recording-play-${recording.id}")
                        .semantics { contentDescription = playDescription },
                shape = CircleShape,
                onClick = {
                    when (playbackStatus) {
                        RecordingPlaybackStatus.PLAYING -> onPausePlayback()
                        RecordingPlaybackStatus.PREPARING,
                        RecordingPlaybackStatus.VERIFYING,
                        -> onStopPlayback()
                        else -> onPlay(recording.id)
                    }
                },
                enabled = recording.canPlay,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            ) {
                Text(
                    when (playbackStatus) {
                        RecordingPlaybackStatus.PLAYING -> "Ⅱ"
                        RecordingPlaybackStatus.PREPARING,
                        RecordingPlaybackStatus.VERIFYING,
                        -> "■"
                        else -> "▶"
                    },
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = recording.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = recordingStatusText(recording.recordingStatus),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        recording.durationMillis?.let { durationMillis ->
            Text(
                text = stringResource(R.string.local_recording_duration, formatDuration(durationMillis)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recording.uploadPolicy != null && recording.transcriptionPolicy != null) {
            Text(
                text =
                    stringResource(
                        R.string.local_recording_policies,
                        policyValueText(
                            uploadPolicyText(recording.uploadPolicy),
                            recording.hasUploadPolicyOverride,
                        ),
                        policyValueText(
                            transcriptionPolicyText(recording.transcriptionPolicy),
                            recording.hasTranscriptionPolicyOverride,
                        ),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        recording.syncStatus?.let { syncStatus ->
            Text(
                text = stringResource(R.string.local_recording_sync, syncStatusText(syncStatus)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (syncStatus == SyncUiStatus.UPLOADING && recording.totalBytes > 0) {
                val percent =
                    ((recording.uploadedBytes.toDouble() / recording.totalBytes) * 100)
                        .toInt()
                        .coerceIn(0, 100)
                Text(
                    text = stringResource(R.string.local_recording_upload_progress, percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (recording.hasTranscript) {
            Text(
                text = stringResource(R.string.local_recording_transcript_available),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        recording.errorCode?.let { errorCode ->
            Text(
                text = stringResource(R.string.local_recording_error, errorCode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recording.syncStatus == SyncUiStatus.BLOCKED || recording.syncStatus == SyncUiStatus.FAILED) {
                OutlinedButton(onClick = { onRetry(recording.id) }) {
                    Text(stringResource(R.string.retry_sync))
                }
            } else if (recording.canStartUpload) {
                OutlinedButton(onClick = { onStartUpload(recording.id) }) {
                    Text(stringResource(R.string.start_upload))
                }
            } else if (recording.canStartTranscription) {
                OutlinedButton(onClick = { onStartTranscription(recording.id) }) {
                    Text(stringResource(R.string.start_transcription))
                }
            }
            if (recording.canPlay) {
                if (playbackStatus != RecordingPlaybackStatus.IDLE) {
                    RecordingPlaybackActions(
                        recordingId = recording.id,
                        playbackState = playbackState,
                        onPlay = onPlay,
                        onPause = onPausePlayback,
                        onStop = onStopPlayback,
                    )
                }
            }
            if (recording.canExport) {
                OutlinedButton(onClick = { onExport(recording.id) }) {
                    Text(stringResource(R.string.export_recording))
                }
            }
        }
    }
}

@Composable
private fun recordingFilterLabel(filter: RecordingFilter): String =
    stringResource(
        when (filter) {
            RecordingFilter.ALL -> R.string.recording_filter_all
            RecordingFilter.NEEDS_ATTENTION -> R.string.recording_filter_attention
            RecordingFilter.WITH_TRANSCRIPT -> R.string.recording_filter_transcripts
        },
    )

@Composable
private fun recordingSortLabel(sort: RecordingSort): String =
    stringResource(
        when (sort) {
            RecordingSort.RECENT -> R.string.recording_sort_recent
            RecordingSort.NAME -> R.string.recording_sort_name
            RecordingSort.DURATION -> R.string.recording_sort_duration
        },
    )

@Composable
private fun RecordingPlaybackActions(
    recordingId: String,
    playbackState: RecordingPlaybackUiState,
    onPlay: (String) -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val isCurrent = playbackState.recordingSessionId == recordingId
    val status = if (isCurrent) playbackState.status else RecordingPlaybackStatus.IDLE
    when (status) {
        RecordingPlaybackStatus.IDLE ->
            OutlinedButton(onClick = { onPlay(recordingId) }) {
                Text(stringResource(R.string.play_recording))
            }

        RecordingPlaybackStatus.VERIFYING,
        RecordingPlaybackStatus.PREPARING,
        -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.recording_playback_preparing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                modifier = Modifier.testTag("recording-stop-$recordingId"),
                onClick = onStop,
            ) {
                Text(stringResource(R.string.stop_playback))
            }
        }

        RecordingPlaybackStatus.PLAYING -> {
            OutlinedButton(onClick = onPause) {
                Text(stringResource(R.string.pause_playback))
            }
            OutlinedButton(
                modifier = Modifier.testTag("recording-stop-$recordingId"),
                onClick = onStop,
            ) {
                Text(stringResource(R.string.stop_playback))
            }
        }

        RecordingPlaybackStatus.PAUSED -> {
            OutlinedButton(onClick = { onPlay(recordingId) }) {
                Text(stringResource(R.string.resume_playback))
            }
            OutlinedButton(
                modifier = Modifier.testTag("recording-stop-$recordingId"),
                onClick = onStop,
            ) {
                Text(stringResource(R.string.stop_playback))
            }
        }

        RecordingPlaybackStatus.FAILED -> {
            Text(
                text = stringResource(R.string.recording_playback_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { onPlay(recordingId) }) {
                Text(stringResource(R.string.retry_playback))
            }
        }
    }
}

@Composable
private fun syncStatusText(status: SyncUiStatus): String =
    stringResource(
        when (status) {
            SyncUiStatus.PENDING -> R.string.sync_pending
            SyncUiStatus.UPLOADING -> R.string.sync_uploading
            SyncUiStatus.UPLOADED -> R.string.sync_uploaded
            SyncUiStatus.TRANSCRIBING -> R.string.sync_transcribing
            SyncUiStatus.BLOCKED -> R.string.sync_blocked
            SyncUiStatus.COMPLETE -> R.string.sync_complete
            SyncUiStatus.FAILED -> R.string.sync_failed
        },
    )

@Composable
private fun SyncedAssetsCard(
    totalCount: Int,
    matchCount: Int,
    assets: List<SyncedAssetSummary>,
    isSearchActive: Boolean,
    onRefresh: () -> Unit,
    onEdit: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.synced_assets),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    if (isSearchActive) {
                        pluralStringResource(
                            R.plurals.synced_assets_search_description,
                            matchCount,
                            matchCount,
                            totalCount,
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.synced_assets_description,
                            totalCount,
                            totalCount,
                        )
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.testTag(REFRESH_SYNCED_ASSETS_TEST_TAG),
            ) {
                Text(stringResource(R.string.refresh_synced_assets))
            }
            if (assets.isEmpty()) {
                Text(
                    text =
                        stringResource(
                            if (isSearchActive && totalCount > 0) {
                                R.string.synced_assets_search_empty
                            } else {
                                R.string.synced_assets_empty
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                assets.forEachIndexed { index, asset ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    SyncedAssetRow(asset, onEdit)
                }
                if (matchCount > assets.size) {
                    Text(
                        text = stringResource(R.string.synced_assets_recent_limit, assets.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncedAssetRow(
    asset: SyncedAssetSummary,
    onEdit: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = asset.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                stringResource(
                    R.string.synced_asset_metadata,
                    if (asset.isTrashed) stringResource(R.string.synced_asset_trashed) else asset.status,
                    asset.language,
                    asset.version,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        asset.durationMillis?.let { durationMillis ->
            Text(
                text = stringResource(R.string.synced_asset_duration, formatDuration(durationMillis)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!asset.isTrashed) {
            OutlinedButton(onClick = { onEdit(asset.id) }) {
                Text(stringResource(R.string.edit_asset_metadata))
            }
        }
    }
}

@Composable
private fun AssetMetadataEditorCard(
    editor: AssetMetadataEditorUiState,
    onTitleChanged: (String) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onCollectionChanged: (String) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
) {
    val isEditing = editor.status == AssetMetadataEditorStatus.EDITING
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.asset_metadata_editor),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    editor.version?.let { version ->
                        stringResource(R.string.asset_metadata_resource, editor.assetId, version)
                    } ?: editor.assetId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (editor.status == AssetMetadataEditorStatus.LOADING) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.asset_metadata_loading))
                }
            }
            OutlinedTextField(
                value = editor.title,
                onValueChange = onTitleChanged,
                modifier = Modifier.fillMaxWidth().testTag(ASSET_METADATA_TITLE_TEST_TAG),
                enabled = isEditing,
                singleLine = true,
                label = { Text(stringResource(R.string.asset_metadata_title)) },
            )
            OutlinedTextField(
                value = editor.language,
                onValueChange = onLanguageChanged,
                modifier = Modifier.fillMaxWidth().testTag(ASSET_METADATA_LANGUAGE_TEST_TAG),
                enabled = isEditing,
                singleLine = true,
                label = { Text(stringResource(R.string.asset_metadata_language)) },
                supportingText = { Text(stringResource(R.string.asset_metadata_language_hint)) },
            )
            OutlinedTextField(
                value = editor.collectionId,
                onValueChange = onCollectionChanged,
                modifier = Modifier.fillMaxWidth().testTag(ASSET_METADATA_COLLECTION_TEST_TAG),
                enabled = isEditing,
                singleLine = true,
                label = { Text(stringResource(R.string.asset_metadata_collection)) },
                supportingText = { Text(stringResource(R.string.asset_metadata_collection_hint)) },
            )
            editor.error?.let { error ->
                Text(
                    text = assetMetadataErrorText(error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (editor.status == AssetMetadataEditorStatus.SAVED) {
                Text(
                    text = stringResource(R.string.asset_metadata_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when (editor.status) {
                    AssetMetadataEditorStatus.EDITING -> {
                        Button(onClick = onSave) {
                            Text(stringResource(R.string.save_asset_metadata))
                        }
                        OutlinedButton(onClick = onClose) {
                            Text(stringResource(R.string.cancel_asset_metadata))
                        }
                    }
                    AssetMetadataEditorStatus.SAVING -> {
                        Button(onClick = {}, enabled = false) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.saving_asset_metadata),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    AssetMetadataEditorStatus.FAILED -> {
                        Button(onClick = onReload) {
                            Text(stringResource(R.string.reload_asset_metadata))
                        }
                        OutlinedButton(onClick = onClose) {
                            Text(stringResource(R.string.close_asset_metadata))
                        }
                    }
                    AssetMetadataEditorStatus.LOADING -> {
                        OutlinedButton(onClick = onClose) {
                            Text(stringResource(R.string.cancel_asset_metadata))
                        }
                    }
                    AssetMetadataEditorStatus.SAVED -> {
                        Button(onClick = onClose) {
                            Text(stringResource(R.string.close_asset_metadata))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun assetMetadataErrorText(error: AssetMetadataEditorError): String =
    stringResource(
        when (error) {
            AssetMetadataEditorError.INVALID_INPUT -> R.string.asset_metadata_invalid
            AssetMetadataEditorError.AUTHENTICATION_REQUIRED -> R.string.asset_metadata_authentication_required
            AssetMetadataEditorError.PERMISSION_DENIED -> R.string.asset_metadata_permission_denied
            AssetMetadataEditorError.NOT_FOUND -> R.string.asset_metadata_not_found
            AssetMetadataEditorError.CONFLICT -> R.string.asset_metadata_conflict
            AssetMetadataEditorError.CONNECTION_FAILED -> R.string.asset_metadata_connection_failed
            AssetMetadataEditorError.TLS_FAILED -> R.string.asset_metadata_tls_failed
            AssetMetadataEditorError.PROTOCOL_MISMATCH -> R.string.asset_metadata_protocol_mismatch
            AssetMetadataEditorError.SECURE_STORAGE_FAILED -> R.string.asset_metadata_secure_storage_failed
            AssetMetadataEditorError.LOAD_FAILED -> R.string.asset_metadata_load_failed
            AssetMetadataEditorError.SAVE_FAILED -> R.string.asset_metadata_save_failed
        },
    )

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
private fun TranscriptCard(
    recordingStatus: RecordingUiStatus,
    transcriptionPolicy: TranscriptionPolicy?,
    language: String?,
    text: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.transcript),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (text == null) {
                Text(
                    text =
                        stringResource(
                            when {
                                transcriptionPolicy == TranscriptionPolicy.DISABLED ->
                                    R.string.transcript_disabled
                                transcriptionPolicy == TranscriptionPolicy.MANUAL ->
                                    R.string.transcript_manual
                                recordingStatus == RecordingUiStatus.SAVED -> R.string.transcript_pending
                                else -> R.string.transcript_empty
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (language != null) {
                    Text(
                        text = stringResource(R.string.transcript_language, language),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun ServerProfileCard(
    profile: ServerProfileSummary,
    canSwitch: Boolean,
    onSelect: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (profile.isActive) {
                Text(
                    text = stringResource(R.string.active_server),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = profile.origin,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text =
                    stringResource(
                        R.string.server_profile_policies,
                        uploadPolicyText(profile.uploadPolicy),
                        transcriptionPolicyText(profile.transcriptionPolicy),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (!profile.isActive) {
                OutlinedButton(
                    onClick = { onSelect(profile.id) },
                    enabled = canSwitch,
                ) {
                    Text(stringResource(R.string.use_server))
                }
            }
        }
    }
}

private fun RecordingUiStatus.allowsProfileSwitch(): Boolean =
    this == RecordingUiStatus.UNAVAILABLE ||
        this == RecordingUiStatus.READY ||
        this == RecordingUiStatus.SAVED ||
        this == RecordingUiStatus.FAILED

@Composable
private fun RecordingCard(
    status: RecordingUiStatus,
    error: String?,
    uploadPolicyOverride: UploadPolicy?,
    transcriptionPolicyOverride: TranscriptionPolicy?,
    onUploadPolicyOverrideChanged: (UploadPolicy?) -> Unit,
    onTranscriptionPolicyOverrideChanged: (TranscriptionPolicy?) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    var elapsedMillis by rememberSaveable { mutableLongStateOf(0L) }
    var showOptions by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(status) {
        if (
            status == RecordingUiStatus.READY ||
            status == RecordingUiStatus.SAVED ||
            status == RecordingUiStatus.FAILED ||
            status == RecordingUiStatus.UNAVAILABLE
        ) {
            elapsedMillis = 0L
        }
        if (status == RecordingUiStatus.RECORDING) {
            while (isActive) {
                delay(250L)
                elapsedMillis += 250L
            }
        }
    }

    val unavailableButtonDescription = stringResource(R.string.recording_unavailable)
    val startButtonDescription = stringResource(R.string.start_recording)
    val pauseButtonDescription = stringResource(R.string.pause_recording)
    val resumeButtonDescription = stringResource(R.string.resume_recording)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (status == RecordingUiStatus.RECORDING || status == RecordingUiStatus.PAUSED) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.recording),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.recording_local_first_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = recordingStatusText(status),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.recording_local_first_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = formatDuration(elapsedMillis),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light,
            )
            RecorderWaveform(
                active = status == RecordingUiStatus.RECORDING,
                elapsedMillis = elapsedMillis,
            )
            if (error != null) {
                Text(
                    text = stringResource(R.string.recording_error_detail, error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (status) {
                    RecordingUiStatus.UNAVAILABLE ->
                        Button(
                            modifier =
                                Modifier
                                    .size(96.dp)
                                    .testTag(RECORD_BUTTON_TEST_TAG)
                                    .semantics { contentDescription = unavailableButtonDescription },
                            shape = CircleShape,
                            onClick = onStart,
                            enabled = false,
                            colors =
                                ButtonDefaults.buttonColors(
                                    disabledContainerColor = RECORDER_ACCENT_RED.copy(alpha = 0.45f),
                                ),
                        ) {
                            Text("●", style = MaterialTheme.typography.headlineLarge)
                        }
                    RecordingUiStatus.READY,
                    RecordingUiStatus.SAVED,
                    RecordingUiStatus.FAILED,
                    ->
                        Button(
                            modifier =
                                Modifier
                                    .size(96.dp)
                                    .testTag(RECORD_BUTTON_TEST_TAG)
                                    .semantics { contentDescription = startButtonDescription },
                            shape = CircleShape,
                            onClick = onStart,
                            colors = ButtonDefaults.buttonColors(containerColor = RECORDER_ACCENT_RED),
                        ) {
                            Text("●", style = MaterialTheme.typography.headlineLarge)
                        }
                    RecordingUiStatus.RECORDING -> {
                        Button(
                            modifier =
                                Modifier
                                    .size(82.dp)
                                    .semantics { contentDescription = pauseButtonDescription },
                            shape = CircleShape,
                            onClick = onPause,
                            colors = ButtonDefaults.buttonColors(containerColor = RECORDER_ACCENT_RED),
                        ) {
                            Text("Ⅱ", style = MaterialTheme.typography.headlineMedium)
                        }
                        OutlinedButton(onClick = onStop) {
                            Text(stringResource(R.string.stop_recording))
                        }
                    }
                    RecordingUiStatus.PAUSED -> {
                        Button(
                            modifier =
                                Modifier
                                    .size(82.dp)
                                    .semantics { contentDescription = resumeButtonDescription },
                            shape = CircleShape,
                            onClick = onResume,
                        ) {
                            Text("▶", style = MaterialTheme.typography.headlineMedium)
                        }
                        OutlinedButton(onClick = onStop) {
                            Text(stringResource(R.string.stop_recording))
                        }
                    }
                    RecordingUiStatus.PAUSING,
                    RecordingUiStatus.RESUMING,
                    -> {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        OutlinedButton(onClick = onStop) {
                            Text(stringResource(R.string.stop_recording))
                        }
                    }
                    RecordingUiStatus.STARTING,
                    RecordingUiStatus.STOPPING,
                    -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
            if (status == RecordingUiStatus.READY ||
                status == RecordingUiStatus.SAVED ||
                status == RecordingUiStatus.FAILED
            ) {
                Text(
                    text = stringResource(R.string.start_recording_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showOptions = !showOptions }) {
                Text(
                    stringResource(
                        if (showOptions) R.string.hide_recording_options else R.string.show_recording_options,
                    ),
                )
            }
            if (showOptions) {
                Text(
                    text = stringResource(R.string.recording_policy_overrides),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.recording_policy_overrides_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                val canChoosePolicies = status != RecordingUiStatus.UNAVAILABLE && status.allowsProfileSwitch()
                PolicySelector(
                    label = stringResource(R.string.upload_policy),
                    value = uploadPolicyOverride,
                    options = listOf<UploadPolicy?>(null) + UploadPolicy.entries,
                    optionLabel = { policy ->
                        policy?.let { uploadPolicyText(it) } ?: stringResource(R.string.server_default)
                    },
                    enabled = canChoosePolicies,
                    selectorTag = "recording_upload_policy_override",
                    onSelected = onUploadPolicyOverrideChanged,
                )
                PolicySelector(
                    label = stringResource(R.string.transcription_policy),
                    value = transcriptionPolicyOverride,
                    options =
                        listOf<TranscriptionPolicy?>(null) +
                            TranscriptionPolicy.entries.filterNot { policy ->
                                policy == TranscriptionPolicy.REALTIME
                            },
                    optionLabel = { policy ->
                        policy?.let { transcriptionPolicyText(it) } ?: stringResource(R.string.server_default)
                    },
                    enabled = canChoosePolicies,
                    selectorTag = "recording_transcription_policy_override",
                    onSelected = onTranscriptionPolicyOverrideChanged,
                )
            }
        }
    }
}

@Composable
private fun RecorderWaveform(
    active: Boolean,
    elapsedMillis: Long,
) {
    val phase = elapsedMillis / 250.0
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .testTag(RECORDER_WAVEFORM_TEST_TAG),
    ) {
        val bars = 48
        val gap = size.width / (bars * 2f)
        val center = size.height / 2f
        repeat(bars) { index ->
            val wave = kotlin.math.abs(kotlin.math.sin(index * 0.9 + phase))
            val height = size.height * (0.16f + wave.toFloat() * if (active) 0.72f else 0.38f)
            drawRoundRect(
                color = if (active) activeColor else idleColor,
                topLeft = Offset(index * gap * 2f, center - height / 2f),
                size = Size(gap, height),
                cornerRadius = CornerRadius(gap / 2f),
            )
        }
    }
}

@Composable
private fun recordingStatusText(status: RecordingUiStatus): String =
    stringResource(
        when (status) {
            RecordingUiStatus.UNAVAILABLE -> R.string.recording_unavailable
            RecordingUiStatus.READY -> R.string.recording_ready
            RecordingUiStatus.STARTING -> R.string.recording_starting
            RecordingUiStatus.RECORDING -> R.string.recording_active
            RecordingUiStatus.PAUSING -> R.string.recording_pausing
            RecordingUiStatus.PAUSED -> R.string.recording_paused
            RecordingUiStatus.RESUMING -> R.string.recording_resuming
            RecordingUiStatus.STOPPING -> R.string.recording_stopping
            RecordingUiStatus.SAVED -> R.string.recording_saved
            RecordingUiStatus.FAILED -> R.string.recording_failed
        },
    )

@Composable
private fun ServerProfileForm(
    draft: ServerProfileDraft,
    isSaving: Boolean,
    error: ServerProfileFormError?,
    onNameChanged: (String) -> Unit,
    onUrlChanged: (String) -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPairingPayloadChanged: (String) -> Unit,
    onCustomCaPemChanged: (String) -> Unit,
    onFingerprintChanged: (String) -> Unit,
    onUploadPolicyChanged: (UploadPolicy) -> Unit,
    onTranscriptionPolicyChanged: (TranscriptionPolicy) -> Unit,
    onSave: () -> Unit,
    onPair: () -> Unit,
    onScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.add_server_profile),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.add_server_profile_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.name,
            onValueChange = onNameChanged,
            modifier = Modifier.fillMaxWidth().testTag(SERVER_NAME_TEST_TAG),
            label = { Text(stringResource(R.string.server_name)) },
            singleLine = true,
            enabled = !isSaving,
        )
        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = onUrlChanged,
            modifier = Modifier.fillMaxWidth().testTag(SERVER_URL_TEST_TAG),
            label = { Text(stringResource(R.string.server_url)) },
            supportingText = { Text(stringResource(R.string.server_url_hint)) },
            singleLine = true,
            enabled = !isSaving,
        )
        OutlinedTextField(
            value = draft.email,
            onValueChange = onEmailChanged,
            modifier = Modifier.fillMaxWidth().testTag(SERVER_EMAIL_TEST_TAG),
            label = { Text(stringResource(R.string.server_email)) },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            singleLine = true,
            enabled = !isSaving,
        )
        OutlinedTextField(
            value = draft.password.value,
            onValueChange = onPasswordChanged,
            modifier = Modifier.fillMaxWidth().testTag(SERVER_PASSWORD_TEST_TAG),
            label = { Text(stringResource(R.string.server_password)) },
            supportingText = { Text(stringResource(R.string.server_password_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                ),
            singleLine = true,
            enabled = !isSaving,
        )
        PolicySelector(
            label = stringResource(R.string.upload_policy),
            value = draft.uploadPolicy,
            options = UploadPolicy.entries,
            optionLabel = { policy -> uploadPolicyText(policy) },
            enabled = !isSaving,
            selectorTag = "server_upload_policy",
            onSelected = onUploadPolicyChanged,
        )
        PolicySelector(
            label = stringResource(R.string.transcription_policy),
            value = draft.transcriptionPolicy,
            options = TranscriptionPolicy.entries.filterNot { policy -> policy == TranscriptionPolicy.REALTIME },
            optionLabel = { policy -> transcriptionPolicyText(policy) },
            enabled = !isSaving,
            selectorTag = "server_transcription_policy",
            onSelected = onTranscriptionPolicyChanged,
        )
        HorizontalDivider()
        Text(
            text = stringResource(R.string.pair_server_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.pair_server_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.pairingPayload.value,
            onValueChange = onPairingPayloadChanged,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(PAIRING_PAYLOAD_TEST_TAG),
            label = { Text(stringResource(R.string.pairing_payload)) },
            supportingText = { Text(stringResource(R.string.pairing_payload_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            minLines = 2,
            maxLines = 4,
            enabled = !isSaving,
        )
        OutlinedButton(
            onClick = onScan,
            modifier = Modifier.testTag(SCAN_PAIRING_TEST_TAG),
            enabled = !isSaving,
        ) {
            Text(stringResource(R.string.scan_pairing_code))
        }
        Button(
            onClick = onPair,
            modifier = Modifier.testTag(PAIR_SERVER_TEST_TAG),
            enabled = !isSaving && draft.pairingPayload.value.isNotBlank(),
        ) {
            Text(stringResource(if (isSaving) R.string.pairing_server else R.string.pair_server))
        }
        HorizontalDivider()
        OutlinedTextField(
            value = draft.customCaPem,
            onValueChange = onCustomCaPemChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.custom_ca_pem)) },
            supportingText = { Text(stringResource(R.string.custom_ca_pem_hint)) },
            minLines = 3,
            maxLines = 8,
            enabled = !isSaving,
        )
        OutlinedTextField(
            value = draft.certificateFingerprint,
            onValueChange = onFingerprintChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.certificate_fingerprint)) },
            supportingText = { Text(stringResource(R.string.certificate_fingerprint_hint)) },
            singleLine = true,
            enabled = !isSaving,
        )
        if (error != null) {
            Text(
                text = serverProfileErrorText(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onSave,
            enabled =
                !isSaving &&
                    draft.name.isNotBlank() &&
                    draft.baseUrl.isNotBlank() &&
                    draft.email.isNotBlank() &&
                    draft.password.value.isNotEmpty(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(stringResource(if (isSaving) R.string.saving_server else R.string.save_server))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PolicySelector(
    label: String,
    value: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    enabled: Boolean,
    selectorTag: String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        modifier = Modifier.testTag(selectorTag),
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = optionLabel(value),
            onValueChange = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun policyValueText(
    value: String,
    isOverride: Boolean,
): String =
    if (isOverride) {
        stringResource(R.string.recording_override_value, value)
    } else {
        value
    }

@Composable
private fun uploadPolicyText(policy: UploadPolicy): String =
    stringResource(
        when (policy) {
            UploadPolicy.MANUAL -> R.string.upload_policy_manual
            UploadPolicy.ANY_NETWORK -> R.string.upload_policy_any_network
            UploadPolicy.WIFI_ONLY -> R.string.upload_policy_wifi_only
            UploadPolicy.CHARGING_AND_WIFI -> R.string.upload_policy_charging_and_wifi
        },
    )

@Composable
private fun transcriptionPolicyText(policy: TranscriptionPolicy): String =
    stringResource(
        when (policy) {
            TranscriptionPolicy.MANUAL -> R.string.transcription_policy_manual
            TranscriptionPolicy.AFTER_UPLOAD -> R.string.transcription_policy_after_upload
            TranscriptionPolicy.WIFI_ONLY -> R.string.transcription_policy_wifi_only
            TranscriptionPolicy.CHARGING_AND_WIFI -> R.string.transcription_policy_charging_and_wifi
            TranscriptionPolicy.DISABLED -> R.string.transcription_policy_disabled
            TranscriptionPolicy.REALTIME -> R.string.transcription_policy_realtime
        },
    )

@Composable
private fun serverProfileErrorText(error: ServerProfileFormError): String =
    stringResource(
        when (error) {
            ServerProfileFormError.INVALID_NAME -> R.string.invalid_server_name
            ServerProfileFormError.INVALID_URL -> R.string.invalid_server_url
            ServerProfileFormError.INVALID_EMAIL -> R.string.invalid_server_email
            ServerProfileFormError.INVALID_PASSWORD -> R.string.invalid_server_password
            ServerProfileFormError.INVALID_PAIRING -> R.string.invalid_pairing_payload
            ServerProfileFormError.INVALID_CUSTOM_CA -> R.string.invalid_custom_ca_pem
            ServerProfileFormError.INVALID_FINGERPRINT -> R.string.invalid_certificate_fingerprint
            ServerProfileFormError.AUTHENTICATION_FAILED -> R.string.server_authentication_failed
            ServerProfileFormError.CONNECTION_FAILED -> R.string.server_connection_failed
            ServerProfileFormError.TLS_FAILED -> R.string.server_tls_failed
            ServerProfileFormError.INCOMPATIBLE_SERVER -> R.string.server_incompatible
            ServerProfileFormError.SECURE_STORAGE_FAILED -> R.string.server_secure_storage_failed
            ServerProfileFormError.SAVE_FAILED -> R.string.server_save_failed
        },
    )

@Composable
private fun StatusCard(
    label: String,
    value: String,
    supportingText: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VoiceAssetAppPreview() {
    VoiceAssetApp()
}
