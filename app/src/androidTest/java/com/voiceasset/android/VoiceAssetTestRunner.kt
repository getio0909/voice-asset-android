package com.voiceasset.android

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.voiceasset.android.recording.RecordingEngine
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.model.RecordingErrorCode
import com.voiceasset.core.model.ServerProfile
import java.io.File

class VoiceAssetTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader,
        className: String,
        context: Context,
    ): Application =
        super.newApplication(
            classLoader,
            TestVoiceAssetApplication::class.java.name,
            context,
        )
}

class TestVoiceAssetApplication : VoiceAssetApplication() {
    override val enableStartupRecoveryAndSync: Boolean
        get() =
            getSharedPreferences(TEST_SETTINGS, MODE_PRIVATE)
                .getBoolean(ENABLE_STARTUP_RECOVERY, false)

    override fun createRecordingEngine(): RecordingEngine = TestRecordingEngine()

    override fun createApiClient(
        profile: ServerProfile,
        credential: BearerCredential?,
    ): VoiceAssetApi = apiFactory?.invoke(profile, credential) ?: super.createApiClient(profile, credential)

    companion object {
        @Volatile
        var apiFactory: ((ServerProfile, BearerCredential?) -> VoiceAssetApi)? = null
    }
}

internal const val TEST_SETTINGS = "voiceasset-instrumentation"
internal const val ENABLE_STARTUP_RECOVERY = "enable_startup_recovery"

private class TestRecordingEngine : RecordingEngine {
    private var started = false
    private var paused = false

    override fun start(
        outputFile: File,
        onError: (RecordingErrorCode) -> Unit,
    ) {
        check(!started)
        outputFile.writeBytes(byteArrayOf(7, 19, 31, 43, 59, 71, 83, 97))
        started = true
    }

    override fun pause() {
        check(started && !paused)
        paused = true
    }

    override fun resume() {
        check(started && paused)
        paused = false
    }

    override fun stop() {
        check(started)
        started = false
    }

    override fun release() {
        started = false
        paused = false
    }
}
