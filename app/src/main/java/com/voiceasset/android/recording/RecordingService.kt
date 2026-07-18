package com.voiceasset.android.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.voiceasset.android.MainActivity
import com.voiceasset.android.R
import com.voiceasset.android.VoiceAssetApplication
import com.voiceasset.android.data.RecordingStore
import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.android.security.StartupSyncPolicy
import com.voiceasset.android.sync.RecordingSyncScheduler
import com.voiceasset.core.model.LocalRecording
import com.voiceasset.core.model.RecordingEffect
import com.voiceasset.core.model.RecordingErrorCode
import com.voiceasset.core.model.RecordingEvent
import com.voiceasset.core.model.RecordingSession
import com.voiceasset.core.model.RecordingSessionId
import com.voiceasset.core.model.RecordingState
import com.voiceasset.core.model.RecordingStateMachine
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private lateinit var recordingStore: RecordingStore
    private lateinit var engineFactory: RecordingEngineFactory
    private lateinit var serverProfiles: ServerProfileRepository
    private lateinit var syncScheduler: RecordingSyncScheduler
    private lateinit var sessionPolicy: StartupSyncPolicy
    private var state: RecordingState = RecordingState.Idle
    private var engine: RecordingEngine? = null
    private var outputFile: File? = null
    private var captureStartedElapsedMillis: Long = 0
    private var pausedAtElapsedMillis: Long? = null
    private var accumulatedPausedMillis: Long = 0

    override fun onCreate() {
        super.onCreate()
        val application = application as VoiceAssetApplication
        recordingStore = application.container.recordings
        serverProfiles = application.container.serverProfiles
        syncScheduler = application.container.syncScheduler
        sessionPolicy = StartupSyncPolicy(application.container.credentials)
        engineFactory = RecordingEngineFactory(application::createRecordingEngine)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        promoteToForeground(getString(R.string.recording_starting))
        if (intent == null) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        scope.launch {
            commandMutex.withLock {
                try {
                    when (intent.action) {
                        ACTION_START -> handleStart(intent)
                        ACTION_PAUSE -> dispatch(RecordingEvent.PauseRequested)
                        ACTION_RESUME -> dispatch(RecordingEvent.ResumeRequested)
                        ACTION_STOP -> dispatch(RecordingEvent.StopRequested)
                        else -> stopForegroundAndSelf()
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    fail(mapFailure(exception))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine?.release()
        engine = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handleStart(intent: Intent) {
        check(state == RecordingState.Idle) { "a recording is already active" }
        val sessionId = RecordingSessionId.parse(requireNotNull(intent.getStringExtra(EXTRA_SESSION_ID)))
        val serverProfileId = intent.getStringExtra(EXTRA_SERVER_PROFILE_ID)?.let(ServerProfileId::parse)
        val startedAtEpochMillis = intent.getLongExtra(EXTRA_STARTED_AT_EPOCH_MILLIS, -1)
        val uploadPolicyOverride =
            intent.getStringExtra(EXTRA_UPLOAD_POLICY_OVERRIDE)?.let(UploadPolicy::valueOf)
        val transcriptionPolicyOverride =
            intent.getStringExtra(EXTRA_TRANSCRIPTION_POLICY_OVERRIDE)?.let(TranscriptionPolicy::valueOf)
        val session =
            RecordingSession(
                id = sessionId,
                fileName = "${sessionId.value}.m4a",
                startedAtEpochMillis = startedAtEpochMillis,
                serverProfileId = serverProfileId,
                uploadPolicyOverride = uploadPolicyOverride,
                transcriptionPolicyOverride = transcriptionPolicyOverride,
            )
        val directory = File(filesDir, RECORDING_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("recording directory is unavailable")
        }
        val file = File(directory, session.fileName)
        check(!file.exists()) { "recording output already exists" }
        outputFile = file

        dispatch(RecordingEvent.Start(session))
    }

    private suspend fun dispatch(event: RecordingEvent) {
        val transition = RecordingStateMachine.transition(state, event)
        val persistedAt = maxOf(System.currentTimeMillis(), stateStartTime(transition.state))
        recordingStore.persist(transition.state, persistedAt)
        state = transition.state
        updateNotification()
        transition.effect?.let { effect -> execute(effect) }
    }

    private suspend fun execute(effect: RecordingEffect) {
        when (effect) {
            is RecordingEffect.StartCapture -> startCapture()
            RecordingEffect.PauseCapture -> pauseCapture()
            RecordingEffect.ResumeCapture -> resumeCapture()
            RecordingEffect.StopCapture -> stopCapture()
        }
    }

    private suspend fun startCapture() {
        val file = checkNotNull(outputFile)
        val recordingEngine = engineFactory.create()
        engine = recordingEngine
        recordingEngine.start(file) { error ->
            scope.launch {
                commandMutex.withLock {
                    fail(error)
                }
            }
        }
        captureStartedElapsedMillis = SystemClock.elapsedRealtime()
        pausedAtElapsedMillis = null
        accumulatedPausedMillis = 0
        dispatch(RecordingEvent.CaptureStarted)
    }

    private suspend fun pauseCapture() {
        checkNotNull(engine).pause()
        pausedAtElapsedMillis = SystemClock.elapsedRealtime()
        dispatch(RecordingEvent.CapturePaused)
    }

    private suspend fun resumeCapture() {
        checkNotNull(engine).resume()
        pausedAtElapsedMillis?.let { pausedAt ->
            accumulatedPausedMillis += SystemClock.elapsedRealtime() - pausedAt
        }
        pausedAtElapsedMillis = null
        dispatch(RecordingEvent.CaptureResumed)
    }

    private suspend fun stopCapture() {
        val stoppedAtElapsedMillis = SystemClock.elapsedRealtime()
        val stoppedAtEpochMillis = System.currentTimeMillis()
        val file = checkNotNull(outputFile)
        try {
            checkNotNull(engine).stop()
        } catch (exception: RuntimeException) {
            file.delete()
            throw RecordingFinalizationException(exception)
        } finally {
            engine?.release()
            engine = null
        }

        val sizeBytes = file.length()
        check(sizeBytes > 0) { "recording file is empty" }
        val pausedMillis =
            accumulatedPausedMillis +
                (pausedAtElapsedMillis?.let { stoppedAtElapsedMillis - it } ?: 0)
        val durationMillis =
            maxOf(
                1,
                stoppedAtElapsedMillis - captureStartedElapsedMillis - pausedMillis,
            )
        val session = (state as RecordingState.Stopping).session
        val recording =
            LocalRecording(
                sessionId = session.id,
                fileName = session.fileName,
                durationMillis = durationMillis,
                sizeBytes = sizeBytes,
                sha256 = file.sha256(),
                stoppedAtEpochMillis = stoppedAtEpochMillis,
            )
        dispatch(RecordingEvent.CaptureStored(recording))
        session.serverProfileId?.let { profileId ->
            if (sessionPolicy.hasReadableSession(profileId)) {
                serverProfiles.find(profileId)?.let { profile ->
                    syncScheduler.enqueue(session, profile)
                }
            }
        }
        stopForegroundAndSelf()
    }

    private suspend fun fail(error: RecordingErrorCode) {
        if (state is RecordingState.Idle || state is RecordingState.Saved || state is RecordingState.Failed) {
            stopForegroundAndSelf()
            return
        }
        runCatching { dispatch(RecordingEvent.CaptureFailed(error)) }
        engine?.release()
        engine = null
        outputFile?.takeIf { file -> file.length() == 0L }?.delete()
        updateNotification()
        stopForegroundAndSelf()
    }

    private fun updateNotification() {
        val text =
            when (state) {
                RecordingState.Idle,
                is RecordingState.Starting,
                -> getString(R.string.recording_starting)
                is RecordingState.Recording -> getString(R.string.recording_active)
                is RecordingState.Pausing,
                is RecordingState.Paused,
                -> getString(R.string.recording_paused)
                is RecordingState.Resuming -> getString(R.string.recording_resuming)
                is RecordingState.Stopping -> getString(R.string.recording_stopping)
                is RecordingState.Saved -> getString(R.string.recording_saved)
                is RecordingState.Failed -> getString(R.string.recording_failed)
            }
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun promoteToForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                Intent(this, MainActivity::class.java),
                PENDING_INTENT_FLAGS,
            )
        val builder =
            Notification
                .Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.recording_notification_title))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(state !is RecordingState.Saved && state !is RecordingState.Failed)
                .setOnlyAlertOnce(true)

        when (state) {
            is RecordingState.Recording -> {
                builder.addAction(notificationAction(ACTION_PAUSE, R.string.pause_recording, REQUEST_PAUSE))
                builder.addAction(notificationAction(ACTION_STOP, R.string.stop_recording, REQUEST_STOP))
            }
            is RecordingState.Paused -> {
                builder.addAction(notificationAction(ACTION_RESUME, R.string.resume_recording, REQUEST_RESUME))
                builder.addAction(notificationAction(ACTION_STOP, R.string.stop_recording, REQUEST_STOP))
            }
            else -> Unit
        }
        return builder.build()
    }

    private fun notificationAction(
        action: String,
        titleResource: Int,
        requestCode: Int,
    ): Notification.Action {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        val pendingIntent = PendingIntent.getForegroundService(this, requestCode, intent, PENDING_INTENT_FLAGS)
        val icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
        return Notification.Action.Builder(icon, getString(titleResource), pendingIntent).build()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.recording_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.recording_notification_channel_description)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stateStartTime(recordingState: RecordingState): Long =
        when (recordingState) {
            is RecordingState.Starting -> recordingState.session.startedAtEpochMillis
            is RecordingState.Recording -> recordingState.session.startedAtEpochMillis
            is RecordingState.Pausing -> recordingState.session.startedAtEpochMillis
            is RecordingState.Paused -> recordingState.session.startedAtEpochMillis
            is RecordingState.Resuming -> recordingState.session.startedAtEpochMillis
            is RecordingState.Stopping -> recordingState.session.startedAtEpochMillis
            else -> 0
        }

    private fun mapFailure(exception: Exception): RecordingErrorCode =
        when (exception) {
            is SecurityException -> RecordingErrorCode.PERMISSION_DENIED
            is RecordingFinalizationException -> RecordingErrorCode.CAPTURE_INTERRUPTED
            is IOException -> RecordingErrorCode.INSUFFICIENT_STORAGE
            is IllegalStateException -> RecordingErrorCode.ENGINE_FAILURE
            else -> RecordingErrorCode.MICROPHONE_UNAVAILABLE
        }

    companion object {
        private const val ACTION_START = "com.voiceasset.android.recording.START"
        private const val ACTION_PAUSE = "com.voiceasset.android.recording.PAUSE"
        private const val ACTION_RESUME = "com.voiceasset.android.recording.RESUME"
        private const val ACTION_STOP = "com.voiceasset.android.recording.STOP"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_SERVER_PROFILE_ID = "server_profile_id"
        private const val EXTRA_STARTED_AT_EPOCH_MILLIS = "started_at_epoch_millis"
        private const val EXTRA_UPLOAD_POLICY_OVERRIDE = "upload_policy_override"
        private const val EXTRA_TRANSCRIPTION_POLICY_OVERRIDE = "transcription_policy_override"
        private const val RECORDING_DIRECTORY = "recordings"
        private const val NOTIFICATION_CHANNEL_ID = "voiceasset-recording"
        private const val NOTIFICATION_ID = 1_001
        private const val REQUEST_OPEN_APP = 10
        private const val REQUEST_PAUSE = 11
        private const val REQUEST_RESUME = 12
        private const val REQUEST_STOP = 13
        private const val PENDING_INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun start(
            context: Context,
            serverProfileId: ServerProfileId? = null,
            uploadPolicyOverride: UploadPolicy? = null,
            transcriptionPolicyOverride: TranscriptionPolicy? = null,
        ) {
            val sessionId = UUID.randomUUID().toString()
            val intent =
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_STARTED_AT_EPOCH_MILLIS, System.currentTimeMillis())
                    .apply {
                        serverProfileId?.let { profileId ->
                            putExtra(EXTRA_SERVER_PROFILE_ID, profileId.value)
                        }
                        uploadPolicyOverride?.let { policy ->
                            putExtra(EXTRA_UPLOAD_POLICY_OVERRIDE, policy.name)
                        }
                        transcriptionPolicyOverride?.let { policy ->
                            putExtra(EXTRA_TRANSCRIPTION_POLICY_OVERRIDE, policy.name)
                        }
                    }
            context.startForegroundService(intent)
        }

        fun pause(context: Context) {
            sendCommand(context, ACTION_PAUSE)
        }

        fun resume(context: Context) {
            sendCommand(context, ACTION_RESUME)
        }

        fun stop(context: Context) {
            sendCommand(context, ACTION_STOP)
        }

        private fun sendCommand(
            context: Context,
            action: String,
        ) {
            context.startForegroundService(Intent(context, RecordingService::class.java).setAction(action))
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private class RecordingFinalizationException(
    cause: RuntimeException,
) : IOException("recording file was not finalized", cause)
