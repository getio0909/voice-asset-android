package com.voiceasset.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordingStateMachineTest {
    private val session =
        RecordingSession(
            id = RecordingSessionId.parse("21d18ca1-b076-4684-a2f2-f8228b7bd153"),
            fileName = "21d18ca1-b076-4684-a2f2-f8228b7bd153.m4a",
            startedAtEpochMillis = 1_000,
        )

    @Test
    fun drivesStartPauseResumeStopAndImmutableSave() {
        val start = RecordingStateMachine.transition(RecordingState.Idle, RecordingEvent.Start(session))
        assertEquals(RecordingState.Starting(session), start.state)
        assertEquals(RecordingEffect.StartCapture(session), start.effect)

        val started = RecordingStateMachine.transition(start.state, RecordingEvent.CaptureStarted)
        assertEquals(RecordingState.Recording(session), started.state)
        assertNull(started.effect)

        val pause = RecordingStateMachine.transition(started.state, RecordingEvent.PauseRequested)
        assertEquals(RecordingState.Pausing(session), pause.state)
        assertEquals(RecordingEffect.PauseCapture, pause.effect)

        val paused = RecordingStateMachine.transition(pause.state, RecordingEvent.CapturePaused)
        assertEquals(RecordingState.Paused(session), paused.state)

        val resume = RecordingStateMachine.transition(paused.state, RecordingEvent.ResumeRequested)
        assertEquals(RecordingState.Resuming(session), resume.state)
        assertEquals(RecordingEffect.ResumeCapture, resume.effect)

        val resumed = RecordingStateMachine.transition(resume.state, RecordingEvent.CaptureResumed)
        assertEquals(RecordingState.Recording(session), resumed.state)

        val stop = RecordingStateMachine.transition(resumed.state, RecordingEvent.StopRequested)
        assertEquals(RecordingState.Stopping(session), stop.state)
        assertEquals(RecordingEffect.StopCapture, stop.effect)

        val recording =
            LocalRecording(
                sessionId = session.id,
                fileName = session.fileName,
                durationMillis = 3_000,
                sizeBytes = 48_000,
                sha256 = "7b".repeat(32),
                stoppedAtEpochMillis = 4_000,
            )
        val saved = RecordingStateMachine.transition(stop.state, RecordingEvent.CaptureStored(recording))
        assertEquals(RecordingState.Saved(recording), saved.state)
        assertNull(saved.effect)
    }

    @Test
    fun stopIsValidWhilePaused() {
        val paused = RecordingState.Paused(session)

        val transition = RecordingStateMachine.transition(paused, RecordingEvent.StopRequested)

        assertEquals(RecordingState.Stopping(session), transition.state)
        assertEquals(RecordingEffect.StopCapture, transition.effect)
    }

    @Test
    fun captureFailureRetainsSessionAndRequiresExplicitReset() {
        val failed =
            RecordingStateMachine.transition(
                RecordingState.Recording(session),
                RecordingEvent.CaptureFailed(RecordingErrorCode.MICROPHONE_UNAVAILABLE),
            )

        assertEquals(
            RecordingState.Failed(session.id, RecordingErrorCode.MICROPHONE_UNAVAILABLE),
            failed.state,
        )
        assertNull(failed.effect)
        assertEquals(
            RecordingTransition(RecordingState.Idle),
            RecordingStateMachine.transition(failed.state, RecordingEvent.Reset),
        )
    }

    @Test
    fun rejectsOutOfOrderEventsAndMismatchedStoredRecording() {
        assertThrows(InvalidRecordingTransition::class.java) {
            RecordingStateMachine.transition(RecordingState.Idle, RecordingEvent.PauseRequested)
        }
        assertThrows(InvalidRecordingTransition::class.java) {
            RecordingStateMachine.transition(
                RecordingState.Stopping(session),
                RecordingEvent.CaptureStored(
                    LocalRecording(
                        sessionId =
                            RecordingSessionId.parse("80e693e6-f8c4-4ae1-bf51-44759fa01a70"),
                        fileName = "other.m4a",
                        durationMillis = 1_000,
                        sizeBytes = 8_000,
                        sha256 = "1f".repeat(32),
                        stoppedAtEpochMillis = 2_000,
                    ),
                ),
            )
        }
    }
}
