package com.voiceasset.android.recording

import com.voiceasset.core.model.RecordingErrorCode
import java.io.File

interface RecordingEngine {
    fun start(
        outputFile: File,
        onError: (RecordingErrorCode) -> Unit,
    )

    fun pause()

    fun resume()

    fun stop()

    fun release()
}

fun interface RecordingEngineFactory {
    fun create(): RecordingEngine
}
