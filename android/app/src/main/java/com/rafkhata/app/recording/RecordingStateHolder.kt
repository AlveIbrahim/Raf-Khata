package com.rafkhata.app.recording

import android.media.MediaRecorder
import com.rafkhata.app.data.settings.MicSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecorderStatus { IDLE, STARTING, RECORDING, PAUSED, STOPPING }

enum class RecorderError { NO_PERMISSION, MIC_UNAVAILABLE, START_NOT_ALLOWED, FAILED }

data class RecorderUi(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val recordingId: String? = null,
    val courseTitle: String? = null,
    val elapsedS: Double = 0.0,
    /** 0..1 for the level meter. */
    val level: Float = 0f,
    val clipping: Boolean = false,
    val silenced: Boolean = false,
    val bookmarks: Int = 0,
    val photos: Int = 0,
    val error: RecorderError? = null,
    /** Set when a recording was saved and queued for upload. */
    val savedRecordingId: String? = null,
) {
    val active: Boolean get() = status != RecorderStatus.IDLE
}

/** Shared between [RecordingService] and the Record screen. */
class RecordingStateHolder {
    private val _state = MutableStateFlow(RecorderUi())
    val state: StateFlow<RecorderUi> = _state.asStateFlow()

    fun update(transform: (RecorderUi) -> RecorderUi) = _state.update(transform)

    fun clearMessages() = _state.update { it.copy(error = null, savedRecordingId = null) }
}

fun MicSource.toAudioSource(): Int = when (this) {
    MicSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    MicSource.MIC -> MediaRecorder.AudioSource.MIC
    MicSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
    MicSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
}
