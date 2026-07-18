package com.voiceasset.android

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServerProfileFormTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun acceptsProfileDraftAndRequestsSave() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var state by
            mutableStateOf(
                initialAppUiState().copy(
                    serverStatus = ServerStatus.CONFIGURED,
                    serverProfiles =
                        listOf(
                            ServerProfileSummary(
                                id = "42ddfd1f-8f9e-4073-9455-b9ea404bd3ce",
                                name = "Active server",
                                origin = "https://active.example.test",
                                isActive = true,
                            ),
                        ),
                    activeServerProfileId = "42ddfd1f-8f9e-4073-9455-b9ea404bd3ce",
                    recordingStatus = RecordingUiStatus.READY,
                ),
            )
        var saveRequested = false
        composeRule.setContent {
            VoiceAssetApp(
                uiState = state,
                onServerNameChanged = { value ->
                    state = state.copy(serverDraft = state.serverDraft.copy(name = value))
                },
                onServerUrlChanged = { value ->
                    state = state.copy(serverDraft = state.serverDraft.copy(baseUrl = value))
                },
                onServerEmailChanged = { value ->
                    state = state.copy(serverDraft = state.serverDraft.copy(email = value))
                },
                onServerPasswordChanged = { value ->
                    state = state.copy(serverDraft = state.serverDraft.copy(password = SecretInput.of(value)))
                },
                onCertificateFingerprintChanged = { value ->
                    state = state.copy(serverDraft = state.serverDraft.copy(certificateFingerprint = value))
                },
                onUploadPolicyChanged = { value ->
                    state = state.copy(serverDraft = state.serverDraft.copy(uploadPolicy = value))
                },
                onTranscriptionPolicyChanged = { value ->
                    state = state.copy(serverDraft = state.serverDraft.copy(transcriptionPolicy = value))
                },
                onRecordingUploadPolicyOverrideChanged = { value ->
                    state = state.copy(recordingUploadPolicyOverride = value)
                },
                onRecordingTranscriptionPolicyOverrideChanged = { value ->
                    state = state.copy(recordingTranscriptionPolicyOverride = value)
                },
                onSaveServer = { saveRequested = true },
            )
        }

        composeRule
            .onNodeWithTag("recording_upload_policy_override")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.upload_policy_charging_and_wifi)).performClick()
        composeRule
            .onNodeWithTag("recording_transcription_policy_override")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.transcription_policy_disabled)).performClick()

        composeRule.onNodeWithText(context.getString(R.string.server_name)).performTextInput("Test")
        composeRule
            .onNodeWithText(context.getString(R.string.server_url))
            .performTextInput("https://example.test")
        composeRule
            .onNodeWithText(context.getString(R.string.server_email))
            .performTextInput("owner@example.test")
        composeRule
            .onNodeWithText(context.getString(R.string.server_password))
            .performTextInput("test-password")
        composeRule
            .onNodeWithText(context.getString(R.string.upload_policy_wifi_only))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.upload_policy_any_network)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.transcription_policy_after_upload))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.transcription_policy_manual)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.save_server)).performClick()

        assertTrue(saveRequested)
        assertEquals(UploadPolicy.ANY_NETWORK, state.serverDraft.uploadPolicy)
        assertEquals(TranscriptionPolicy.MANUAL, state.serverDraft.transcriptionPolicy)
        assertEquals(UploadPolicy.CHARGING_AND_WIFI, state.recordingUploadPolicyOverride)
        assertEquals(TranscriptionPolicy.DISABLED, state.recordingTranscriptionPolicyOverride)
    }

    @Test
    fun configuredStateShowsPersistedOrigin() {
        val origin = "https://api.getio.net:10443"
        composeRule.setContent {
            VoiceAssetApp(
                uiState =
                    initialAppUiState().copy(
                        serverStatus = ServerStatus.CONFIGURED,
                        serverProfiles =
                            listOf(
                                ServerProfileSummary(
                                    id = "42ddfd1f-8f9e-4073-9455-b9ea404bd3ce",
                                    name = "Remote test server",
                                    origin = origin,
                                ),
                            ),
                    ),
            )
        }

        composeRule.onNodeWithText(origin).assertIsDisplayed()
    }

    @Test
    fun acceptsMaskedOneTimePairingPayloadAndRequestsPairing() {
        var state by mutableStateOf(initialAppUiState())
        var pairingRequested = false
        val payload = "voiceasset://pair?secret=va_pair_${"A".repeat(43)}"
        composeRule.setContent {
            VoiceAssetApp(
                uiState = state,
                onPairingPayloadChanged = { value ->
                    state = state.copy(serverDraft = state.serverDraft.copy(pairingPayload = SecretInput.of(value)))
                },
                onPairServer = { pairingRequested = true },
            )
        }

        composeRule
            .onNodeWithTag(PAIRING_PAYLOAD_TEST_TAG)
            .performScrollTo()
            .performTextInput(payload)
        assertEquals(0, composeRule.onAllNodesWithText(payload).fetchSemanticsNodes().size)
        composeRule
            .onNodeWithTag(PAIR_SERVER_TEST_TAG)
            .performScrollTo()
            .performClick()

        assertEquals(payload, state.serverDraft.pairingPayload.value)
        assertTrue(pairingRequested)
    }
}
