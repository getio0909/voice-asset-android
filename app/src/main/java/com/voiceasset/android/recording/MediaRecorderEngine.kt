package com.voiceasset.android.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.voiceasset.core.model.RecordingErrorCode
import java.io.File

class MediaRecorderEngine(
    private val context: Context,
) : RecordingEngine {
    private var recorder: MediaRecorder? = null

    override fun start(
        outputFile: File,
        onError: (RecordingErrorCode) -> Unit,
    ) {
        check(recorder == null) { "recording engine is already active" }
        val mediaRecorder = createRecorder()
        try {
            mediaRecorder.setOnErrorListener { _, _, _ ->
                onError(RecordingErrorCode.ENGINE_FAILURE)
            }
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioChannels(AUDIO_CHANNEL_COUNT)
            mediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE_HZ)
            mediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            mediaRecorder.setOutputFile(outputFile)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                mediaRecorder.setPrivacySensitive(true)
            }
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
        } catch (exception: Exception) {
            mediaRecorder.release()
            throw exception
        }
    }

    override fun pause() {
        requireRecorder().pause()
    }

    override fun resume() {
        requireRecorder().resume()
    }

    override fun stop() {
        requireRecorder().stop()
    }

    override fun release() {
        recorder?.apply {
            setOnErrorListener(null)
            release()
        }
        recorder = null
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    private fun requireRecorder(): MediaRecorder =
        checkNotNull(recorder) {
            "recording engine is not active"
        }

    private companion object {
        const val AUDIO_CHANNEL_COUNT = 1
        const val AUDIO_SAMPLE_RATE_HZ = 44_100
        const val AUDIO_BIT_RATE = 128_000
    }
}
