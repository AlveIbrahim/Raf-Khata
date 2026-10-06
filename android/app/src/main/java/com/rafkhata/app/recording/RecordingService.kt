package com.rafkhata.app.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.notify.Notifications
import com.rafkhata.core.AudioLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service (type microphone) that owns the [AudioRecorder] while a class is recorded.
 * It is started from the Record screen; Android 14+ does not allow starting it from the background,
 * so after a crash recording is not restarted automatically (the saved part is recovered instead).
 */
class RecordingService : LifecycleService() {
    private var recorder: AudioRecorder? = null
    private var recordingId: String? = null
    private var courseTitle: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private var lastUiUpdate = 0L
    private var silencedSince: Double? = null

    private val state get() = container.recordingState

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_RECORDING_ID))
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> stop()
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked right below
    private fun start(id: String?) {
        if (recorder != null) return
        if (id == null) {
            stopSelf()
            return
        }
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_RECORDING,
                buildNotification(paused = false, elapsedS = 0.0),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        } catch (_: Exception) {
            // SecurityException without the microphone permission, or
            // ForegroundServiceStartNotAllowedException when not started from the app on screen.
            fail(id, if (hasMicPermission()) RecorderError.START_NOT_ALLOWED else RecorderError.NO_PERMISSION)
            return
        }
        if (!hasMicPermission()) {
            fail(id, RecorderError.NO_PERMISSION)
            return
        }
        recordingId = id
        lifecycleScope.launch {
            val recording = container.recordings.get(id)
            if (recording == null) {
                fail(id, RecorderError.FAILED)
                return@launch
            }
            courseTitle = recording.courseTitle ?: recording.title
            val source = container.settings.current().micSource.toAudioSource()
            val newRecorder = AudioRecorder(container.recordings.audioDir(id), source, ContextCompat.getMainExecutor(this@RecordingService), listener)
            try {
                if (ContextCompat.checkSelfPermission(this@RecordingService, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    newRecorder.start()
                } else {
                    throw SecurityException("RECORD_AUDIO not granted")
                }
            } catch (_: Exception) {
                fail(id, RecorderError.MIC_UNAVAILABLE)
                return@launch
            }
            recorder = newRecorder
            acquireWakeLock()
            state.update {
                RecorderUi(status = RecorderStatus.RECORDING, recordingId = id, courseTitle = courseTitle)
            }
            Notifications.post(this@RecordingService, Notifications.ID_RECORDING, buildNotification(false, 0.0))
        }
    }

    private val listener = object : AudioRecorder.Listener {
        override fun onAudio(levelDb: Double, clipped: Double, recordedSeconds: Double) {
            val now = System.currentTimeMillis()
            if (now - lastUiUpdate < 90) return
            lastUiUpdate = now
            state.update {
                it.copy(elapsedS = recordedSeconds, level = AudioLevels.meterFraction(levelDb), clipping = clipped > 0.01)
            }
        }

        override fun onSilenced(silenced: Boolean) {
            val id = recordingId ?: return
            val at = recorder?.recordedSeconds ?: 0.0
            state.update { it.copy(silenced = silenced) }
            if (silenced && silencedSince == null) {
                silencedSince = at
                lifecycleScope.launch {
                    container.recordings.addBookmark(id, at, "note", getString(R.string.bookmark_mic_silenced))
                }
            } else if (!silenced) {
                silencedSince = null
            }
        }

        override fun onError(error: Throwable) {
            // Keep what was recorded; the user sees the error and the recording is uploaded.
            lifecycleScope.launch {
                state.update { it.copy(error = RecorderError.FAILED) }
                stop()
            }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun setPaused(paused: Boolean) {
        val current = recorder ?: return
        if (stopping) return
        current.setPaused(paused)
        state.update { it.copy(status = if (paused) RecorderStatus.PAUSED else RecorderStatus.RECORDING, level = 0f) }
        Notifications.post(this, Notifications.ID_RECORDING, buildNotification(paused, current.recordedSeconds))
    }

    private fun stop() {
        val current = recorder
        val id = recordingId
        if (current == null || id == null) {
            if (!stopping) stopSelf()
            return
        }
        if (stopping) return
        stopping = true
        state.update { it.copy(status = RecorderStatus.STOPPING) }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { current.stop() }
            recorder = null
            val saved = withContext(Dispatchers.IO) { container.recordings.finish(id) }
            if (saved) container.uploads.enqueue(id)
            state.update { RecorderUi(savedRecordingId = if (saved) id else null, error = it.error) }
            releaseWakeLock()
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(id: String, error: RecorderError) {
        state.update { RecorderUi(error = error) }
        container.appScope.launch { container.recordings.delete(id) }
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed without a stop request: close the files and queue what was recorded.
        val current = recorder
        val id = recordingId
        if (current != null && id != null && !stopping) {
            recorder = null
            val app = container
            app.appScope.launch {
                withContext(Dispatchers.IO) { current.stop() }
                if (app.recordings.finish(id)) app.uploads.enqueue(id)
                app.recordingState.update { RecorderUi(savedRecordingId = id) }
            }
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RafKhata:recording").apply {
            setReferenceCounted(false)
            acquire(MAX_RECORDING_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(paused: Boolean, elapsedS: Double): Notification {
        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_rk)
            .setContentTitle(getString(if (paused) R.string.recording_paused else R.string.recording_in_progress))
            .setContentText(courseTitle ?: getString(R.string.app_name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this, 10) { putExtra(Notifications.EXTRA_OPEN, Notifications.OPEN_RECORD) })
        if (!paused) {
            builder.setUsesChronometer(true).setWhen(System.currentTimeMillis() - (elapsedS * 1000).toLong()).setShowWhen(true)
        }
        builder.addAction(
            0,
            getString(if (paused) R.string.resume else R.string.pause),
            servicePendingIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 11),
        )
        builder.addAction(0, getString(R.string.stop), servicePendingIntent(ACTION_STOP, 12))
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val ACTION_START = "com.rafkhata.app.recording.START"
        private const val ACTION_PAUSE = "com.rafkhata.app.recording.PAUSE"
        private const val ACTION_RESUME = "com.rafkhata.app.recording.RESUME"
        private const val ACTION_STOP = "com.rafkhata.app.recording.STOP"
        private const val EXTRA_RECORDING_ID = "recording_id"
        private const val MAX_RECORDING_MS = 5 * 60 * 60 * 1000L

        /** Must be called while the app is on screen. */
        fun start(context: Context, recordingId: String) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RECORDING_ID, recordingId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)

        fun resume(context: Context) = send(context, ACTION_RESUME)

        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }
    }
}
