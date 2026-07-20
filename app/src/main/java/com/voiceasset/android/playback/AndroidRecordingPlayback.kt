package com.voiceasset.android.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        engineFactory = AndroidRecordingPlaybackEngineFactory(),
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

private class AndroidRecordingPlaybackEngineFactory :
    RecordingPlaybackEngineFactory,
    RecordingPlaybackDecoderModeSink {
    override var decoderMode = RecordingPlaybackDecoderMode.SYSTEM_DEFAULT

    override fun create(): RecordingPlaybackEngine = MediaPlayerRecordingPlaybackEngine(decoderMode)
}

private class MediaPlayerRecordingPlaybackEngine(
    private val decoderMode: RecordingPlaybackDecoderMode,
) : RecordingPlaybackEngine {
    private val player = MediaPlayer()
    private var released = false

    override fun prepare(
        file: File,
        listener: RecordingPlaybackEngine.Listener,
    ) {
        if (decoderMode == RecordingPlaybackDecoderMode.HARDWARE_PREFERRED) {
            // MediaPlayer owns the actual codec selection on Android. Probe the
            // file so hardware preference is best-effort, but never reject a
            // playable file when a device does not expose a hardware decoder.
            hasHardwareDecoder(file)
        }
        player.reset()
        player.setAudioAttributes(playbackAudioAttributes)
        player.setOnPreparedListener { listener.onPrepared() }
        player.setOnCompletionListener { listener.onCompletion() }
        player.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
            listener.onError()
            true
        }
        try {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (exception: Exception) {
            Log.e(TAG, "MediaPlayer prepare failed", exception)
            throw exception
        }
    }

    private fun hasHardwareDecoder(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val mimeType =
                (0 until extractor.trackCount)
                    .asSequence()
                    .mapNotNull { index -> extractor.getTrackFormat(index).getString("mime") }
                    .firstOrNull { mime -> mime.startsWith("audio/") }
                    ?: return false
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info.isHardwareAccelerated) &&
                    info.supportedTypes.any { type ->
                        type.equals(mimeType, ignoreCase = true)
                    }
            }
        } catch (_: Exception) {
            false
        } finally {
            extractor.release()
        }
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

    override fun request(): Boolean {
        val result = audioManager.requestAudioFocus(request)
        Log.i(TAG, "Audio focus request result=$result")
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }
}

private const val TAG = "VoiceAssetPlayback"
