package com.voiceasset.core.api

import com.voiceasset.core.model.RecordingSessionId

@ConsistentCopyVisibility
data class SyncIdempotencyKeys private constructor(
    val asset: String,
    val upload: String,
    val transcription: String,
) {
    companion object {
        fun forRecording(
            recordingSessionId: RecordingSessionId,
            transcriptionGeneration: Int = 0,
        ): SyncIdempotencyKeys {
            require(transcriptionGeneration >= 0) { "transcription generation must not be negative" }
            val suffix = recordingSessionId.value
            val transcriptionSuffix =
                if (transcriptionGeneration == 0) {
                    suffix
                } else {
                    "$suffix-retry-$transcriptionGeneration"
                }
            return SyncIdempotencyKeys(
                asset = "android-asset-$suffix",
                upload = "android-upload-$suffix",
                transcription = "android-transcription-$transcriptionSuffix",
            )
        }
    }
}
