package com.voiceasset.android

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.android.administration.ProviderProfileFamily
import com.voiceasset.android.playback.RecordingPlaybackDecoderMode
import com.voiceasset.android.playback.RecordingPlaybackStatus
import com.voiceasset.android.playback.RecordingPlaybackUiState
import com.voiceasset.core.api.ProviderHealthStatus
import com.voiceasset.core.api.ProviderProfileState
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val DEVICE_PROFILE_ID = "90000000-0000-4000-8000-000000000009"
private const val CURRENT_DEVICE_SESSION_ID = "91000000-0000-4000-8000-000000000009"
private const val REMOTE_DEVICE_SESSION_ID = "92000000-0000-4000-8000-000000000009"

@RunWith(AndroidJUnit4::class)
class VoiceAssetAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun startupShowsInitializedAndServerNotConfiguredStates() {
        val initialized = context.getString(R.string.initialized)
        val serverNotConfigured = context.getString(R.string.server_not_configured)
        val recordingReady = context.getString(R.string.recording_ready)
        val localFirstDescription = context.getString(R.string.recording_local_first_description)
        val localRecordings = context.getString(R.string.local_recordings)
        val optionalServerDescription = context.getString(R.string.add_server_profile_description)

        composeRule.setContent { VoiceAssetApp() }
        composeRule.onNodeWithTag(RECORD_FAB_TEST_TAG).performClick()
        composeRule.onNodeWithText(initialized).assertIsDisplayed()
        composeRule.onNodeWithText(serverNotConfigured).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(localFirstDescription).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(recordingReady).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(localRecordings).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(optionalServerDescription).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun recorderFirstSurfaceOffersLanguageSwitchAndNativeControls() {
        var selectedLanguage: AppLanguage? = null

        composeRule.setContent {
            VoiceAssetApp(onLanguageSelected = { selectedLanguage = it })
        }

        composeRule.onNodeWithTag(RECORDER_SETTINGS_TEST_TAG).performClick()
        composeRule.onNodeWithTag(LANGUAGE_SELECTOR_TEST_TAG).performClick()
        composeRule.onNodeWithTag(LANGUAGE_CHINESE_TEST_TAG).performClick()
        composeRule.runOnIdle {
            assertEquals(AppLanguage.SIMPLIFIED_CHINESE, selectedLanguage)
        }
        composeRule.onNodeWithTag(RECORDER_BACK_TEST_TAG).performClick()
        composeRule.onNodeWithTag(RECORD_FAB_TEST_TAG).performClick()
        composeRule.onNodeWithTag(RECORD_BUTTON_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(RECORDER_WAVEFORM_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun recorderShellOffersSearchFiltersSettingsAndOneRecordAction() {
        composeRule.setContent { VoiceAssetApp() }

        composeRule.onNodeWithTag(RECORDER_SETTINGS_TEST_TAG).performClick()
        composeRule.onNodeWithText(context.getString(R.string.settings_tab)).assertIsDisplayed()
        composeRule.onNodeWithTag(RECORDER_BACK_TEST_TAG).performClick()
        composeRule.onNodeWithText(context.getString(R.string.recordings_tab)).assertIsDisplayed()
        composeRule.onNodeWithTag(RECORDER_SEARCH_TEST_TAG).performClick()
        composeRule.onNodeWithTag(OFFLINE_LIBRARY_SEARCH_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("recording-filter").performClick()
        composeRule.onNodeWithText(context.getString(R.string.recording_filter_all)).assertIsDisplayed()
    }

    @Test
    fun settingsExposePlaybackDecoderChoices() {
        var selectedDecoder: RecordingPlaybackDecoderMode? = null

        composeRule.setContent {
            VoiceAssetApp(
                onPlaybackDecoderModeChanged = { selectedDecoder = it },
            )
        }

        composeRule.onNodeWithTag(RECORDER_SETTINGS_TEST_TAG).performClick()
        composeRule
            .onNodeWithTag("playback-decoder-hardware_preferred")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(RecordingPlaybackDecoderMode.HARDWARE_PREFERRED, selectedDecoder)
        }
    }

    @Test
    fun currentProfileCanBeReconnectedWithoutCreatingAnotherProfile() {
        var observedEmail = ""
        var observedPassword = ""
        var submitted = false
        composeRule.setContent {
            var email by remember { mutableStateOf("") }
            var password by remember { mutableStateOf("") }
            VoiceAssetApp(
                uiState =
                    initialAppUiState().copy(
                        serverStatus = ServerStatus.CONFIGURED,
                        serverProfiles =
                            listOf(
                                ServerProfileSummary(
                                    id = DEVICE_PROFILE_ID,
                                    name = "Primary server",
                                    origin = "https://api.getio.net:10443",
                                    isActive = true,
                                ),
                            ),
                        activeServerProfileId = DEVICE_PROFILE_ID,
                        serverSessionReconnect =
                            ServerSessionReconnectUiState(
                                email = email,
                                password = SecretInput.of(password),
                            ),
                    ),
                onSessionReconnectEmailChanged = { value ->
                    observedEmail = value
                    email = value
                },
                onSessionReconnectPasswordChanged = { value ->
                    observedPassword = value
                    password = value
                },
                onReconnectActiveServerProfile = { submitted = true },
            )
        }

        composeRule.onNodeWithTag(RECORDER_SETTINGS_TEST_TAG).performClick()
        composeRule
            .onNodeWithTag(SESSION_RECONNECT_EMAIL_TEST_TAG)
            .performScrollTo()
            .performTextInput("owner@example.test")
        composeRule
            .onNodeWithTag(SESSION_RECONNECT_PASSWORD_TEST_TAG)
            .performScrollTo()
            .performTextInput("new-password")
        composeRule
            .onNodeWithTag(SESSION_RECONNECT_SUBMIT_TEST_TAG)
            .performScrollTo()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(SESSION_RECONNECT_SUBMIT_TEST_TAG)
            .performClick()
        composeRule.runOnIdle {
            assertEquals("owner@example.test", observedEmail)
            assertEquals("new-password", observedPassword)
            assertEquals(true, submitted)
        }
    }

    @Test
    fun deviceSessionRevocationRequiresExplicitConfirmation() {
        var refreshed = false
        var confirmedId: String? = null
        composeRule.setContent {
            var deviceSessions by
                remember {
                    mutableStateOf(
                        DeviceSessionsUiState(
                            status = DeviceSessionsStatus.READY,
                            items =
                                listOf(
                                    DeviceSessionSummary(
                                        id = CURRENT_DEVICE_SESSION_ID,
                                        deviceName = "Pixel 9",
                                        current = true,
                                        lastSeenAt = "2026-07-18T05:30:00Z",
                                        refreshExpiresAt = "2026-08-17T05:30:00Z",
                                    ),
                                    DeviceSessionSummary(
                                        id = REMOTE_DEVICE_SESSION_ID,
                                        deviceName = "Tablet",
                                        current = false,
                                        lastSeenAt = "2026-07-17T05:30:00Z",
                                        refreshExpiresAt = "2026-08-16T05:30:00Z",
                                    ),
                                ),
                        ),
                    )
                }
            VoiceAssetApp(
                uiState =
                    initialAppUiState().copy(
                        serverStatus = ServerStatus.CONFIGURED,
                        serverProfiles =
                            listOf(
                                ServerProfileSummary(
                                    id = DEVICE_PROFILE_ID,
                                    name = "Primary server",
                                    origin = "https://api.getio.net:10443",
                                    isActive = true,
                                ),
                            ),
                        activeServerProfileId = DEVICE_PROFILE_ID,
                        deviceSessions = deviceSessions,
                    ),
                onRefreshDeviceSessions = {
                    refreshed = true
                    deviceSessions = deviceSessions.copy(status = DeviceSessionsStatus.READY)
                },
                onRequestDeviceSessionRevocation = { id ->
                    deviceSessions = deviceSessions.copy(pendingRevocationId = id)
                },
                onCancelDeviceSessionRevocation = {
                    deviceSessions = deviceSessions.copy(pendingRevocationId = null)
                },
                onConfirmDeviceSessionRevocation = { confirmedId = deviceSessions.pendingRevocationId },
            )
        }

        composeRule.onNodeWithTag(RECORDER_SETTINGS_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(REFRESH_DEVICE_SESSIONS_TEST_TAG)
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(DEVICE_SESSION_REVOKE_TEST_TAG_PREFIX + REMOTE_DEVICE_SESSION_ID)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Revoke Tablet?").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(true, refreshed)
            assertEquals(null, confirmedId)
        }

        composeRule.onNodeWithTag(CANCEL_DEVICE_SESSION_REVOKE_TEST_TAG).performClick()
        composeRule
            .onNodeWithTag(DEVICE_SESSION_REVOKE_TEST_TAG_PREFIX + REMOTE_DEVICE_SESSION_ID)
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CONFIRM_DEVICE_SESSION_REVOKE_TEST_TAG).performClick()
        composeRule.runOnIdle {
            assertEquals(REMOTE_DEVICE_SESSION_ID, confirmedId)
        }
    }

    @Test
    fun cachedContentIsReadableAfterUiRecreation() {
        var selectedProfileId: String? = null
        var searchQuery: String? = null
        var retriedRecordingId: String? = null
        var playedRecordingId: String? = null
        var exportedRecordingId: String? = null
        var stoppedHiddenPlayback = false
        var editedAssetId: String? = null
        var metadataTitle: String? = null
        var metadataSaved = false
        var refreshedAssets = false
        var refreshedAdministration = false
        var providerUpdate: Pair<String, Boolean>? = null
        var providerHealthCheckId: String? = null
        var retriedAdministrationJobId: String? = null
        composeRule.setContent {
            var assetTitle by remember { mutableStateOf("Latest server title") }
            var composedSearchQuery by remember { mutableStateOf("") }
            VoiceAssetApp(
                initialAppUiState().copy(
                    serverStatus = ServerStatus.CONFIGURED,
                    offlineLibrarySearchQuery = composedSearchQuery,
                    serverProfiles =
                        listOf(
                            ServerProfileSummary(
                                id = "82636d78-31f6-4349-899a-a87bb8bb6814",
                                name = "Primary server",
                                origin = "https://api.getio.net:10443",
                                isActive = true,
                            ),
                            ServerProfileSummary(
                                id = "93636d78-31f6-4349-899a-a87bb8bb6814",
                                name = "Archive server",
                                origin = "https://archive.example.test",
                            ),
                        ),
                    activeServerProfileId = "82636d78-31f6-4349-899a-a87bb8bb6814",
                    recordingStatus = RecordingUiStatus.SAVED,
                    transcriptRevisionId = "70000000-0000-4000-8000-000000000007",
                    transcriptLanguage = "en-US",
                    transcriptText = "Cached offline transcript",
                    localRecordingCount = 2,
                    localRecordings =
                        listOf(
                            LocalRecordingSummary(
                                id = "50000000-0000-4000-8000-000000000005",
                                fileName = "field-note.m4a",
                                recordingStatus = RecordingUiStatus.SAVED,
                                durationMillis = 65_000,
                                syncStatus = SyncUiStatus.COMPLETE,
                                uploadedBytes = 1_024,
                                totalBytes = 1_024,
                                hasTranscript = true,
                                errorCode = null,
                                uploadPolicy = UploadPolicy.MANUAL,
                                transcriptionPolicy = TranscriptionPolicy.DISABLED,
                                hasUploadPolicyOverride = true,
                                hasTranscriptionPolicyOverride = true,
                                canPlay = true,
                                canExport = true,
                            ),
                            LocalRecordingSummary(
                                id = "51000000-0000-4000-8000-000000000005",
                                fileName = "failed-note.m4a",
                                recordingStatus = RecordingUiStatus.SAVED,
                                durationMillis = 30_000,
                                syncStatus = SyncUiStatus.FAILED,
                                uploadedBytes = 0,
                                totalBytes = 2_048,
                                hasTranscript = false,
                                errorCode = "retry_exhausted",
                            ),
                        ),
                    syncedAssetCount = 1,
                    syncedAssets =
                        listOf(
                            SyncedAssetSummary(
                                id = "60000000-0000-4000-8000-000000000006",
                                title = "Synced interview",
                                language = "en-US",
                                status = "ready",
                                durationMillis = 65_000,
                                version = 3,
                                isTrashed = false,
                            ),
                        ),
                    assetMetadataEditor =
                        AssetMetadataEditorUiState(
                            assetId = "60000000-0000-4000-8000-000000000006",
                            status = AssetMetadataEditorStatus.EDITING,
                            title = assetTitle,
                            language = "en-US",
                            collectionId = "",
                            version = 3,
                            error = null,
                        ),
                    mobileAdministration =
                        MobileAdministrationUiState(
                            status = MobileAdministrationStatus.READY,
                            systemStatus =
                                MobileSystemStatusSummary(
                                    generatedAt = "2026-07-16T08:02:00Z",
                                    activeUsers = 2,
                                    assetCount = 4,
                                    storageObjectCount = 3,
                                    storageBytes = 2_048,
                                    transcriptCount = 2,
                                    revisionCount = 3,
                                    jobCount = 1,
                                    queuedJobCount = 0,
                                    runningJobCount = 0,
                                    retryWaitJobCount = 0,
                                    failedJobCount = 1,
                                    enabledAsrCount = 0,
                                    enabledLlmCount = 1,
                                ),
                            jobs =
                                listOf(
                                    MobileAdministrationJobSummary(
                                        id = "61000000-0000-4000-8000-000000000006",
                                        kind = "mock_transcribe",
                                        state = "failed",
                                        attempts = 3,
                                        maxAttempts = 3,
                                        retryable = true,
                                        lastErrorCode = "provider_unavailable",
                                        updatedAt = "2026-07-16T08:01:00Z",
                                    ),
                                ),
                            providers =
                                listOf(
                                    MobileProviderProfileSummary(
                                        id = "62000000-0000-4000-8000-000000000006",
                                        family = ProviderProfileFamily.ASR,
                                        providerId = "mock_asr",
                                        displayName = "Mock ASR",
                                        state = ProviderProfileState.DISABLED,
                                        priority = 100,
                                        version = 1,
                                        secretConfigured = true,
                                        healthStatus = ProviderHealthStatus.HEALTHY,
                                        healthCheckedAt = "2026-07-16T08:03:00Z",
                                    ),
                                ),
                        ),
                ),
                playbackState =
                    RecordingPlaybackUiState(
                        recordingSessionId = "52000000-0000-4000-8000-000000000005",
                        status = RecordingPlaybackStatus.PLAYING,
                    ),
                onServerSelected = { profileId -> selectedProfileId = profileId },
                onOfflineLibrarySearchQueryChanged = { value ->
                    searchQuery = value
                    composedSearchQuery = value
                },
                onRefreshSyncedAssets = { refreshedAssets = true },
                onRefreshMobileAdministration = { refreshedAdministration = true },
                onSetProviderProfileEnabled = { profileId, enabled ->
                    providerUpdate = profileId to enabled
                },
                onCheckProviderProfileHealth = { profileId ->
                    providerHealthCheckId = profileId
                },
                onRetryAdministrationJob = { jobId ->
                    retriedAdministrationJobId = jobId
                },
                onEditAssetMetadata = { assetId -> editedAssetId = assetId },
                onAssetMetadataTitleChanged = { value ->
                    metadataTitle = value
                    assetTitle = value
                },
                onSaveAssetMetadata = { metadataSaved = true },
                onRetryRecordingSync = { recordingId -> retriedRecordingId = recordingId },
                onPlayRecording = { recordingId -> playedRecordingId = recordingId },
                onStopRecordingPlayback = { stoppedHiddenPlayback = true },
                onExportRecording = { recordingId -> exportedRecordingId = recordingId },
            )
        }

        composeRule.onNodeWithText("Cached offline transcript").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(OFFLINE_LIBRARY_SEARCH_TEST_TAG).performTextReplacement("field")
        composeRule.waitUntil(timeoutMillis = 5_000) { searchQuery == "field" }
        composeRule.runOnIdle {
            assertEquals("field", searchQuery)
        }
        composeRule
            .onAllNodesWithText("Playback controls for a recording hidden by the current search.")
            .assertCountEquals(1)
        composeRule
            .onNodeWithTag("recording-stop-52000000-0000-4000-8000-000000000005")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(true, stoppedHiddenPlayback)
        }
        composeRule.onNodeWithTag(RECORDER_SETTINGS_TEST_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Language: en-US").assertIsDisplayed()
        composeRule
            .onNodeWithTag(REFRESH_MOBILE_ADMINISTRATION_TEST_TAG)
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(PROVIDER_PROFILE_ACTION_TEST_TAG_PREFIX + "62000000-0000-4000-8000-000000000006")
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(PROVIDER_PROFILE_HEALTH_TEST_TAG_PREFIX + "62000000-0000-4000-8000-000000000006")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(true, refreshedAdministration)
            assertEquals("62000000-0000-4000-8000-000000000006" to true, providerUpdate)
            assertEquals("62000000-0000-4000-8000-000000000006", providerHealthCheckId)
        }
        composeRule.onNodeWithText("Health: healthy · Checked 2026-07-16T08:03:00Z").assertIsDisplayed()
        composeRule
            .onNodeWithTag(ADMINISTRATION_JOB_RETRY_TEST_TAG_PREFIX + "61000000-0000-4000-8000-000000000006")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals("61000000-0000-4000-8000-000000000006", retriedAdministrationJobId)
        }
        composeRule.onNodeWithText("Synced interview").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(REFRESH_SYNCED_ASSETS_TEST_TAG).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(true, refreshedAssets)
        }
        composeRule.onNodeWithText("Edit metadata").performScrollTo().performClick()
        composeRule.onNodeWithTag(ASSET_METADATA_TITLE_TEST_TAG).performScrollTo().performTextReplacement("Renamed interview")
        composeRule.onNodeWithText("Save metadata").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals("60000000-0000-4000-8000-000000000006", editedAssetId)
            assertEquals("Renamed interview", metadataTitle)
            assertEquals(true, metadataSaved)
        }
        composeRule.onAllNodesWithText("Duration: 1:05").assertCountEquals(2)
        repeat(4) {
            composeRule.onRoot().performTouchInput { swipeUp() }
        }
        composeRule.onAllNodesWithText("field-note.m4a").assertCountEquals(1)
        composeRule.onAllNodesWithText("Sync: Complete").assertCountEquals(1)
        composeRule.onAllNodesWithText("Transcript available offline").assertCountEquals(1)
        composeRule
            .onAllNodesWithText("Upload: Manual (recording override) · Transcription: Disabled (recording override)")
            .assertCountEquals(1)
        composeRule.onNodeWithText("Play").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals("50000000-0000-4000-8000-000000000005", playedRecordingId)
        }
        composeRule.onNodeWithText("Export").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals("50000000-0000-4000-8000-000000000005", exportedRecordingId)
        }
        composeRule.onNodeWithText("Retry sync").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals("51000000-0000-4000-8000-000000000005", retriedRecordingId)
        }
        composeRule.onNodeWithText("Use this server").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals("93636d78-31f6-4349-899a-a87bb8bb6814", selectedProfileId)
        }
    }
}
