package com.voiceasset.android.recording

import android.Manifest
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.voiceasset.android.MainActivity
import com.voiceasset.android.TestVoiceAssetApplication
import com.voiceasset.android.data.StoredRecordingStatus
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecordingServiceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun foregroundServicePersistsSavedRecordingWithTestEngine() =
        runBlocking {
            val application = composeRule.activity.application as TestVoiceAssetApplication
            grantRuntimePermissions()
            val profileId = ServerProfileId.parse("9a8309a8-596b-487d-8614-bc71f6542217")
            application.container.serverProfiles.save(testProfile(profileId))
            application.container.activeServerProfile.set(profileId)

            composeRule.activity.runOnUiThread {
                RecordingService.start(
                    context = composeRule.activity,
                    serverProfileId = profileId,
                    uploadPolicyOverride = UploadPolicy.MANUAL,
                    transcriptionPolicyOverride = TranscriptionPolicy.DISABLED,
                )
            }
            val active =
                withTimeout(5_000) {
                    application.container.recordings.observeAll().first { recordings ->
                        recordings.any { recording -> recording.status == StoredRecordingStatus.RECORDING }
                    }
                }.first { recording -> recording.status == StoredRecordingStatus.RECORDING }

            RecordingService.stop(composeRule.activity)

            val saved =
                withTimeout(5_000) {
                    application.container.recordings.observeAll().first { recordings ->
                        recordings.any { recording ->
                            recording.session.id == active.session.id &&
                                recording.status == StoredRecordingStatus.SAVED
                        }
                    }
                }.first { recording -> recording.session.id == active.session.id }
            assertEquals(StoredRecordingStatus.SAVED, saved.status)
            assertEquals(UploadPolicy.MANUAL, saved.session.uploadPolicyOverride)
            assertEquals(TranscriptionPolicy.DISABLED, saved.session.transcriptionPolicyOverride)
            val recording = requireNotNull(saved.recording)
            assertTrue(recording.sizeBytes > 0)
            assertEquals(64, recording.sha256.length)
        }

    @Test
    fun foregroundServicePersistsLocalRecordingWithoutServerProfile() =
        runBlocking {
            val application = composeRule.activity.application as TestVoiceAssetApplication
            grantRuntimePermissions()

            composeRule.activity.runOnUiThread {
                RecordingService.start(
                    context = composeRule.activity,
                    serverProfileId = null,
                    uploadPolicyOverride = UploadPolicy.MANUAL,
                    transcriptionPolicyOverride = TranscriptionPolicy.DISABLED,
                )
            }
            val active =
                withTimeout(5_000) {
                    application.container.recordings.observeAll().first { recordings ->
                        recordings.any {
                            it.status == StoredRecordingStatus.RECORDING &&
                                it.session.serverProfileId == null
                        }
                    }
                }.first { recording -> recording.status == StoredRecordingStatus.RECORDING }

            RecordingService.stop(composeRule.activity)

            val saved =
                withTimeout(5_000) {
                    application.container.recordings.observeAll().first { recordings ->
                        recordings.any { recording ->
                            recording.session.id == active.session.id &&
                                recording.status == StoredRecordingStatus.SAVED
                        }
                    }
                }.first { recording -> recording.session.id == active.session.id }
            assertEquals(null, saved.session.serverProfileId)
            assertEquals(StoredRecordingStatus.SAVED, saved.status)
            assertTrue(requireNotNull(saved.recording).sizeBytes > 0)
            assertEquals(null, application.container.syncTasks.find(active.session.id))
        }

    private fun grantRuntimePermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = composeRule.activity.packageName
        instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun testProfile(id: ServerProfileId): ServerProfile =
        ServerProfile.create(
            id = id,
            name = "Recording test",
            baseUrl = "https://example.test",
            authenticationMode = AuthenticationMode.LOCAL_SESSION,
            defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
            defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
            customCaPem = null,
            certificateFingerprint = null,
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )
}
