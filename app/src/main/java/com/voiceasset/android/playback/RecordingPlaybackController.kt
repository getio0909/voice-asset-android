package com.voiceasset.android.playback

import com.voiceasset.android.export.RecordingFileResolver
import com.voiceasset.android.export.VerifiedRecordingFile
import com.voiceasset.core.model.RecordingSessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class RecordingPlaybackUiState(
    val recordingSessionId: String? = null,
    val status: RecordingPlaybackStatus = RecordingPlaybackStatus.IDLE,
)

enum class RecordingPlaybackStatus {
    IDLE,
    VERIFYING,
    PREPARING,
    PLAYING,
    PAUSED,
    FAILED,
}

internal enum class PlaybackFocusChange {
    GAIN,
    LOSS_TRANSIENT,
    LOSS,
}

internal interface RecordingPlaybackEngine {
    fun prepare(
        file: File,
        listener: Listener,
    )

    fun start()

    fun pause()

    fun release()

    interface Listener {
        fun onPrepared()

        fun onCompletion()

        fun onError()
    }
}

internal fun interface RecordingPlaybackEngineFactory {
    fun create(): RecordingPlaybackEngine
}

internal interface RecordingPlaybackFocus {
    fun request(): Boolean

    fun abandon()
}

internal fun interface RecordingPlaybackFocusFactory {
    fun create(onChange: (PlaybackFocusChange) -> Unit): RecordingPlaybackFocus
}

internal class RecordingPlaybackController(
    private val scope: CoroutineScope,
    private val verifier: RecordingFileResolver,
    private val engineFactory: RecordingPlaybackEngineFactory,
    focusFactory: RecordingPlaybackFocusFactory,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(RecordingPlaybackUiState())
    private val focus = focusFactory.create(::onFocusChange)
    private var engine: RecordingPlaybackEngine? = null
    private var verificationJob: Job? = null
    private var generation = 0L
    private var resumeOnFocusGain = false
    private var hasAudioFocus = false

    val state: StateFlow<RecordingPlaybackUiState> = mutableState.asStateFlow()

    fun play(recordingSessionId: RecordingSessionId) {
        val current = mutableState.value
        if (current.recordingSessionId == recordingSessionId.value) {
            when (current.status) {
                RecordingPlaybackStatus.PAUSED -> {
                    resume()
                    return
                }

                RecordingPlaybackStatus.VERIFYING,
                RecordingPlaybackStatus.PREPARING,
                RecordingPlaybackStatus.PLAYING,
                -> return

                RecordingPlaybackStatus.IDLE,
                RecordingPlaybackStatus.FAILED,
                -> Unit
            }
        }

        stopInternal(updateState = false)
        val requestGeneration = generation
        mutableState.value =
            RecordingPlaybackUiState(
                recordingSessionId = recordingSessionId.value,
                status = RecordingPlaybackStatus.VERIFYING,
            )
        verificationJob =
            scope.launch {
                val verified =
                    try {
                        verifier.resolve(recordingSessionId)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        null
                    }
                if (requestGeneration != generation) {
                    return@launch
                }
                if (verified == null || !requestFocus()) {
                    fail(recordingSessionId.value, requestGeneration)
                    return@launch
                }
                prepare(recordingSessionId.value, verified, requestGeneration)
            }
    }

    fun pause() {
        val current = mutableState.value
        val currentEngine = engine
        if (current.status != RecordingPlaybackStatus.PLAYING || currentEngine == null) {
            return
        }
        resumeOnFocusGain = false
        try {
            currentEngine.pause()
            mutableState.value = current.copy(status = RecordingPlaybackStatus.PAUSED)
            abandonFocus()
        } catch (_: Exception) {
            fail(current.recordingSessionId, generation)
        }
    }

    fun stop() {
        stopInternal(updateState = true)
    }

    override fun close() {
        stopInternal(updateState = true)
    }

    private fun resume() {
        val current = mutableState.value
        val currentEngine = engine
        if (
            current.status != RecordingPlaybackStatus.PAUSED ||
            current.recordingSessionId == null ||
            currentEngine == null ||
            resumeOnFocusGain
        ) {
            return
        }
        resumeOnFocusGain = false
        if (!requestFocus()) {
            fail(current.recordingSessionId, generation)
            return
        }
        try {
            currentEngine.start()
            mutableState.value = current.copy(status = RecordingPlaybackStatus.PLAYING)
        } catch (_: Exception) {
            fail(current.recordingSessionId, generation)
        }
    }

    private fun prepare(
        recordingSessionId: String,
        verified: VerifiedRecordingFile,
        requestGeneration: Long,
    ) {
        val nextEngine =
            try {
                engineFactory.create()
            } catch (_: Exception) {
                fail(recordingSessionId, requestGeneration)
                return
            }
        engine = nextEngine
        mutableState.value =
            RecordingPlaybackUiState(
                recordingSessionId = recordingSessionId,
                status = RecordingPlaybackStatus.PREPARING,
            )
        val listener =
            object : RecordingPlaybackEngine.Listener {
                override fun onPrepared() {
                    if (!isCurrent(nextEngine, requestGeneration)) {
                        nextEngine.releaseSafely()
                        return
                    }
                    if (resumeOnFocusGain) {
                        mutableState.value =
                            RecordingPlaybackUiState(
                                recordingSessionId = recordingSessionId,
                                status = RecordingPlaybackStatus.PAUSED,
                            )
                        return
                    }
                    try {
                        nextEngine.start()
                        mutableState.value =
                            RecordingPlaybackUiState(
                                recordingSessionId = recordingSessionId,
                                status = RecordingPlaybackStatus.PLAYING,
                            )
                    } catch (_: Exception) {
                        fail(recordingSessionId, requestGeneration)
                    }
                }

                override fun onCompletion() {
                    if (isCurrent(nextEngine, requestGeneration)) {
                        stopInternal(updateState = true)
                    } else {
                        nextEngine.releaseSafely()
                    }
                }

                override fun onError() {
                    if (isCurrent(nextEngine, requestGeneration)) {
                        fail(recordingSessionId, requestGeneration)
                    } else {
                        nextEngine.releaseSafely()
                    }
                }
            }
        try {
            nextEngine.prepare(verified.file, listener)
        } catch (_: Exception) {
            fail(recordingSessionId, requestGeneration)
        }
    }

    private fun onFocusChange(change: PlaybackFocusChange) {
        when (change) {
            PlaybackFocusChange.GAIN -> {
                val current = mutableState.value
                val currentEngine = engine
                if (resumeOnFocusGain) {
                    when (current.status) {
                        RecordingPlaybackStatus.PREPARING -> resumeOnFocusGain = false
                        RecordingPlaybackStatus.PAUSED -> {
                            if (currentEngine != null) {
                                resumeOnFocusGain = false
                                try {
                                    currentEngine.start()
                                    mutableState.value = current.copy(status = RecordingPlaybackStatus.PLAYING)
                                } catch (_: Exception) {
                                    fail(current.recordingSessionId, generation)
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }

            PlaybackFocusChange.LOSS_TRANSIENT -> {
                val current = mutableState.value
                val currentEngine = engine
                when (current.status) {
                    RecordingPlaybackStatus.PREPARING -> resumeOnFocusGain = true
                    RecordingPlaybackStatus.PLAYING ->
                        if (currentEngine != null) {
                            try {
                                currentEngine.pause()
                                resumeOnFocusGain = true
                                mutableState.value = current.copy(status = RecordingPlaybackStatus.PAUSED)
                            } catch (_: Exception) {
                                fail(current.recordingSessionId, generation)
                            }
                        }

                    else -> Unit
                }
            }

            PlaybackFocusChange.LOSS -> stopInternal(updateState = true)
        }
    }

    private fun isCurrent(
        candidate: RecordingPlaybackEngine,
        requestGeneration: Long,
    ): Boolean = engine === candidate && requestGeneration == generation

    private fun requestFocus(): Boolean {
        if (hasAudioFocus) {
            return true
        }
        hasAudioFocus = runCatching(focus::request).getOrDefault(false)
        return hasAudioFocus
    }

    private fun abandonFocus() {
        if (hasAudioFocus) {
            hasAudioFocus = false
            runCatching(focus::abandon)
        }
    }

    private fun fail(
        recordingSessionId: String?,
        requestGeneration: Long,
    ) {
        if (requestGeneration != generation) {
            return
        }
        verificationJob = null
        engine.releaseSafely()
        engine = null
        resumeOnFocusGain = false
        abandonFocus()
        mutableState.value =
            RecordingPlaybackUiState(
                recordingSessionId = recordingSessionId,
                status = RecordingPlaybackStatus.FAILED,
            )
    }

    private fun stopInternal(updateState: Boolean) {
        generation += 1
        verificationJob?.cancel()
        verificationJob = null
        engine.releaseSafely()
        engine = null
        resumeOnFocusGain = false
        abandonFocus()
        if (updateState) {
            mutableState.value = RecordingPlaybackUiState()
        }
    }
}

private fun RecordingPlaybackEngine?.releaseSafely() {
    runCatching { this?.release() }
}
