package com.voiceasset.core.model

@JvmInline
value class RecordingSessionId private constructor(
    val value: String,
) {
    companion object {
        fun parse(value: String): RecordingSessionId = RecordingSessionId(parseCanonicalUuid(value, "recording session id"))
    }
}

data class RecordingSession(
    val id: RecordingSessionId,
    val fileName: String,
    val startedAtEpochMillis: Long,
    val serverProfileId: ServerProfileId? = null,
    val uploadPolicyOverride: UploadPolicy? = null,
    val transcriptionPolicyOverride: TranscriptionPolicy? = null,
) {
    init {
        validateRecordingFileName(fileName)
        require(startedAtEpochMillis >= 0) { "recording start timestamp must not be negative" }
    }
}

data class LocalRecording(
    val sessionId: RecordingSessionId,
    val fileName: String,
    val durationMillis: Long,
    val sizeBytes: Long,
    val sha256: String,
    val stoppedAtEpochMillis: Long,
) {
    init {
        validateRecordingFileName(fileName)
        require(durationMillis > 0) { "recording duration must be positive" }
        require(sizeBytes > 0) { "recording size must be positive" }
        require(SHA256.matches(sha256)) { "recording hash must be a SHA-256 value" }
        require(stoppedAtEpochMillis >= 0) { "recording stop timestamp must not be negative" }
    }
}

enum class RecordingErrorCode {
    PERMISSION_DENIED,
    MICROPHONE_UNAVAILABLE,
    INSUFFICIENT_STORAGE,
    CAPTURE_INTERRUPTED,
    ENGINE_FAILURE,
}

sealed interface RecordingState {
    data object Idle : RecordingState

    data class Starting(
        val session: RecordingSession,
    ) : RecordingState

    data class Recording(
        val session: RecordingSession,
    ) : RecordingState

    data class Pausing(
        val session: RecordingSession,
    ) : RecordingState

    data class Paused(
        val session: RecordingSession,
    ) : RecordingState

    data class Resuming(
        val session: RecordingSession,
    ) : RecordingState

    data class Stopping(
        val session: RecordingSession,
    ) : RecordingState

    data class Saved(
        val recording: LocalRecording,
    ) : RecordingState

    data class Failed(
        val sessionId: RecordingSessionId,
        val code: RecordingErrorCode,
    ) : RecordingState
}

sealed interface RecordingEvent {
    data class Start(
        val session: RecordingSession,
    ) : RecordingEvent

    data object CaptureStarted : RecordingEvent

    data object PauseRequested : RecordingEvent

    data object CapturePaused : RecordingEvent

    data object ResumeRequested : RecordingEvent

    data object CaptureResumed : RecordingEvent

    data object StopRequested : RecordingEvent

    data class CaptureStored(
        val recording: LocalRecording,
    ) : RecordingEvent

    data class CaptureFailed(
        val code: RecordingErrorCode,
    ) : RecordingEvent

    data object Reset : RecordingEvent
}

sealed interface RecordingEffect {
    data class StartCapture(
        val session: RecordingSession,
    ) : RecordingEffect

    data object PauseCapture : RecordingEffect

    data object ResumeCapture : RecordingEffect

    data object StopCapture : RecordingEffect
}

data class RecordingTransition(
    val state: RecordingState,
    val effect: RecordingEffect? = null,
)

class InvalidRecordingTransition(
    state: RecordingState,
    event: RecordingEvent,
    reason: String? = null,
) : IllegalStateException(
        buildString {
            append("${state::class.simpleName} does not accept ${event::class.simpleName}")
            if (reason != null) {
                append(": $reason")
            }
        },
    )

object RecordingStateMachine {
    fun transition(
        state: RecordingState,
        event: RecordingEvent,
    ): RecordingTransition =
        when {
            state is RecordingState.Idle && event is RecordingEvent.Start ->
                RecordingTransition(
                    state = RecordingState.Starting(event.session),
                    effect = RecordingEffect.StartCapture(event.session),
                )

            state is RecordingState.Starting && event is RecordingEvent.CaptureStarted ->
                RecordingTransition(RecordingState.Recording(state.session))

            state is RecordingState.Recording && event is RecordingEvent.PauseRequested ->
                RecordingTransition(
                    state = RecordingState.Pausing(state.session),
                    effect = RecordingEffect.PauseCapture,
                )

            state is RecordingState.Pausing && event is RecordingEvent.CapturePaused ->
                RecordingTransition(RecordingState.Paused(state.session))

            state is RecordingState.Paused && event is RecordingEvent.ResumeRequested ->
                RecordingTransition(
                    state = RecordingState.Resuming(state.session),
                    effect = RecordingEffect.ResumeCapture,
                )

            state is RecordingState.Resuming && event is RecordingEvent.CaptureResumed ->
                RecordingTransition(RecordingState.Recording(state.session))

            state.canStop() && event is RecordingEvent.StopRequested ->
                RecordingTransition(
                    state = RecordingState.Stopping(state.activeSession()),
                    effect = RecordingEffect.StopCapture,
                )

            state is RecordingState.Stopping && event is RecordingEvent.CaptureStored -> {
                if (event.recording.sessionId != state.session.id) {
                    throw InvalidRecordingTransition(
                        state,
                        event,
                        "stored recording belongs to another session",
                    )
                }
                if (event.recording.fileName != state.session.fileName) {
                    throw InvalidRecordingTransition(
                        state,
                        event,
                        "stored recording file does not match the active session",
                    )
                }
                if (event.recording.stoppedAtEpochMillis < state.session.startedAtEpochMillis) {
                    throw InvalidRecordingTransition(
                        state,
                        event,
                        "recording stop timestamp precedes its start",
                    )
                }
                RecordingTransition(RecordingState.Saved(event.recording))
            }

            state.hasActiveSession() && event is RecordingEvent.CaptureFailed ->
                RecordingTransition(
                    RecordingState.Failed(state.activeSession().id, event.code),
                )

            (state is RecordingState.Failed || state is RecordingState.Saved) &&
                event is RecordingEvent.Reset -> RecordingTransition(RecordingState.Idle)

            else -> throw InvalidRecordingTransition(state, event)
        }
}

private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

private fun validateRecordingFileName(fileName: String) {
    require(fileName.isNotBlank()) { "recording file name must not be blank" }
    require(fileName == fileName.trim()) { "recording file name must not have surrounding whitespace" }
    require(fileName.length <= 255) { "recording file name must not exceed 255 characters" }
    require(fileName != "." && fileName != "..") { "recording file name must identify a file" }
    require('/' !in fileName && '\\' !in fileName) { "recording file name must not contain a path" }
    require(fileName.none(Char::isISOControl)) {
        "recording file name must not contain control characters"
    }
}

private fun RecordingState.hasActiveSession(): Boolean =
    this is RecordingState.Starting ||
        this is RecordingState.Recording ||
        this is RecordingState.Pausing ||
        this is RecordingState.Paused ||
        this is RecordingState.Resuming ||
        this is RecordingState.Stopping

private fun RecordingState.canStop(): Boolean =
    this is RecordingState.Recording ||
        this is RecordingState.Pausing ||
        this is RecordingState.Paused ||
        this is RecordingState.Resuming

private fun RecordingState.activeSession(): RecordingSession =
    when (this) {
        is RecordingState.Starting -> session
        is RecordingState.Recording -> session
        is RecordingState.Pausing -> session
        is RecordingState.Paused -> session
        is RecordingState.Resuming -> session
        is RecordingState.Stopping -> session
        else -> error("recording state does not contain an active session")
    }
