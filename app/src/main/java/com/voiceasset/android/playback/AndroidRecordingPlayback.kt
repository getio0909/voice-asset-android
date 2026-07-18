package com.voiceasset.android.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.export.RecordingFileVerifier
import kotlinx.coroutines.CoroutineScope
import java.io.File

internal fun createRecordingPlaybackController(
    context: Context,
    recordings: RecordingStore,
    scope: CoroutineScope,
): RecordingPlaybackController =
    RecordingPlaybackController(
        scope = scope,
        verifier = RecordingFileVerifier(context, recordings),
        engineFactory = RecordingPlaybackEngineFactory(::MediaPlayerRecordingPlaybackEngine),
        focusFactory =
            RecordingPlaybackFocusFactory { listener ->
                AndroidRecordingPlaybackFocus(context, listener)
            },
    )

private val playbackAudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

private class MediaPlayerRecordingPlaybackEngine : RecordingPlaybackEngine {
    private val player = MediaPlayer()
    private var released = false

    override fun prepare(
        file: File,
        listener: RecordingPlaybackEngine.Listener,
    ) {
        player.setAudioAttributes(playbackAudioAttributes)
        player.setOnPreparedListener { listener.onPrepared() }
        player.setOnCompletionListener { listener.onCompletion() }
        player.setOnErrorListener { _, _, _ ->
            listener.onError()
            true
        }
        player.setDataSource(file.absolutePath)
        player.prepareAsync()
    }

    override fun start() {
        player.start()
    }

    override fun pause() {
        player.pause()
    }

    override fun release() {
        if (!released) {
            released = true
            player.release()
        }
    }
}

private class AndroidRecordingPlaybackFocus(
    context: Context,
    onChange: (PlaybackFocusChange) -> Unit,
) : RecordingPlaybackFocus {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val request =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(playbackAudioAttributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(
                { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> onChange(PlaybackFocusChange.GAIN)
                        AudioManager.AUDIOFOCUS_LOSS -> onChange(PlaybackFocusChange.LOSS)
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                        -> onChange(PlaybackFocusChange.LOSS_TRANSIENT)
                    }
                },
                Handler(Looper.getMainLooper()),
            ).build()

    override fun request(): Boolean = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
