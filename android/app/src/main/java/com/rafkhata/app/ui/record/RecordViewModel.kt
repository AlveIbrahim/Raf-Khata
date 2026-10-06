package com.rafkhata.app.ui.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rafkhata.app.data.api.CourseDto
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.recording.RecorderError
import com.rafkhata.app.recording.RecorderStatus
import com.rafkhata.app.recording.RecorderUi
import com.rafkhata.app.recording.RecordingService
import com.rafkhata.app.recording.SoundCheckRunner
import com.rafkhata.app.recording.toAudioSource
import com.rafkhata.core.Routine
import com.rafkhata.core.SoundCheck
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime

class RecordViewModel(private val app: AppContainer, initialCourseId: String?) : ViewModel() {
    data class State(
        val courseId: String? = null,
        val coursePicked: Boolean = false,
        val title: String = "",
        val consentChecked: Boolean = false,
        val soundChecking: Boolean = false,
        val soundLevel: Float = 0f,
        val soundCheck: SoundCheck? = null,
        val soundCheckFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(State(courseId = initialCourseId, coursePicked = initialCourseId != null))
    val state: StateFlow<State> = _state.asStateFlow()
    val courses: StateFlow<List<CourseDto>?> = app.courses.courses
    val recorder: StateFlow<RecorderUi> = app.recordingState.state

    private var pendingPhoto: Pair<String, File>? = null
    private var soundJob: Job? = null

    init {
        if (!recorder.value.active) app.recordingState.clearMessages()
        viewModelScope.launch {
            val cached = app.courses.cached()
            if (!_state.value.coursePicked) pickFromRoutine(cached)
            runCatching { app.courses.refresh() }.onSuccess { if (!_state.value.coursePicked) pickFromRoutine(it) }
        }
    }

    private fun pickFromRoutine(courses: List<CourseDto>?) {
        if (courses.isNullOrEmpty()) return
        val now = LocalDateTime.now()
        val slot = Routine.currentCourse(
            app.courses.slots(courses),
            Routine.fromIsoDayOfWeek(now.dayOfWeek.value),
            now.hour * 60 + now.minute,
        )
        if (slot != null) _state.update { it.copy(courseId = slot.courseId, coursePicked = true) }
    }

    fun selectCourse(id: String?) = _state.update { it.copy(courseId = id, coursePicked = true, consentChecked = false) }

    fun setTitle(title: String) = _state.update { it.copy(title = title.take(300)) }

    fun setConsent(checked: Boolean) = _state.update { it.copy(consentChecked = checked) }

    fun course(): CourseDto? = _state.value.courseId?.let { id -> courses.value?.firstOrNull { it.id == id } }

    fun canStart(): Boolean = course()?.consentConfirmed == true || _state.value.consentChecked

    /** Must be called while the screen is visible and after RECORD_AUDIO was granted. */
    fun start() {
        if (recorder.value.active || !canStart()) return
        soundJob?.cancel()
        val course = course()
        val s = _state.value
        app.recordingState.update { RecorderUi(status = RecorderStatus.STARTING, courseTitle = course?.title) }
        viewModelScope.launch {
            val recording = app.recordings.create(
                courseId = course?.id,
                courseTitle = course?.title,
                title = s.title.trim(),
                consentConfirmed = true,
                notesLang = null,
            )
            if (course != null && !course.consentConfirmed) {
                launch { runCatching { app.courses.confirmConsent(course.id) } }
            }
            try {
                RecordingService.start(app.appContext, recording.id)
            } catch (_: Exception) {
                app.recordings.delete(recording.id)
                app.recordingState.update { RecorderUi(error = RecorderError.START_NOT_ALLOWED) }
            }
        }
    }

    fun pause() = RecordingService.pause(app.appContext)

    fun resume() = RecordingService.resume(app.appContext)

    fun stop() = RecordingService.stop(app.appContext)

    fun bookmark(kind: String) {
        val r = recorder.value
        val id = r.recordingId ?: return
        app.recordingState.update { it.copy(bookmarks = it.bookmarks + 1) }
        viewModelScope.launch { app.recordings.addBookmark(id, r.elapsedS, kind) }
    }

    /** A content URI for the camera app to save a board photo into. */
    fun preparePhoto(): Uri? {
        val id = recorder.value.recordingId ?: return null
        val (photoId, file) = app.recordings.newPhotoFile(id)
        pendingPhoto = photoId to file
        return FileProvider.getUriForFile(app.appContext, app.appContext.packageName + ".files", file)
    }

    fun onPhotoResult(saved: Boolean) {
        val (photoId, file) = pendingPhoto ?: return
        pendingPhoto = null
        val r = recorder.value
        val id = r.recordingId
        if (!saved || id == null) {
            file.delete()
            return
        }
        app.recordingState.update { it.copy(photos = it.photos + 1) }
        viewModelScope.launch { app.recordings.addPhoto(id, photoId, file, r.elapsedS) }
    }

    @SuppressLint("MissingPermission") // checked first
    fun runSoundCheck() {
        if (soundJob?.isActive == true || recorder.value.active) return
        if (ContextCompat.checkSelfPermission(app.appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        soundJob = viewModelScope.launch {
            _state.update { it.copy(soundChecking = true, soundCheck = null, soundCheckFailed = false) }
            val source = app.settings.current().micSource.toAudioSource()
            runCatching { SoundCheckRunner.run(source, SOUND_CHECK_SECONDS) { level -> _state.update { it.copy(soundLevel = level) } } }
                .onSuccess { result -> _state.update { it.copy(soundChecking = false, soundCheck = result, soundLevel = 0f) } }
                .onFailure { _state.update { it.copy(soundChecking = false, soundCheckFailed = true, soundLevel = 0f) } }
        }
    }

    fun clearRecorderError() = app.recordingState.clearMessages()

    private companion object {
        const val SOUND_CHECK_SECONDS = 8
    }
}
