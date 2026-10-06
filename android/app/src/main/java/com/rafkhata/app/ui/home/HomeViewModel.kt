package com.rafkhata.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rafkhata.app.data.api.CourseDto
import com.rafkhata.app.data.api.DeadlineDto
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.LectureDto
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.data.db.RecordingEntity
import com.rafkhata.app.data.settings.AppSettings
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.isProcessing
import com.rafkhata.core.Routine
import com.rafkhata.core.Slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** The class happening now, or the next one in the routine. */
data class ClassInfo(val course: CourseDto, val slot: Slot, val isNow: Boolean, val daysAway: Int)

class HomeViewModel(private val app: AppContainer) : ViewModel() {
    data class State(
        val lectures: List<LectureDto>? = null,
        val deadlines: List<DeadlineDto> = emptyList(),
        val refreshing: Boolean = false,
        val error: ErrorMessage? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val recordings: StateFlow<List<RecordingEntity>> =
        app.recordings.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recorder = app.recordingState.state

    val settings: StateFlow<AppSettings> =
        app.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val minuteTicker = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(60_000)
        }
    }

    val classInfo: StateFlow<ClassInfo?> = combine(app.courses.courses, minuteTicker) { courses, now ->
        courses?.let { classAt(it, now) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            app.lectures.cachedList()?.let { cached -> _state.update { it.copy(lectures = it.lectures ?: cached) } }
            app.lectures.cachedDeadlines()?.let { cached -> _state.update { it.copy(deadlines = upcoming(cached)) } }
            app.courses.cached()
            refresh()
        }
        // Notes became ready (push) or an upload finished: show the new state.
        viewModelScope.launch { app.lectureEvents.collect { refresh(quiet = true) } }
        viewModelScope.launch {
            app.recordings.all.map { list -> list.size }.distinctUntilChanged().drop(1).collect { refresh(quiet = true) }
        }
    }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(refreshing = true) }
            val result = runCatching {
                val lectures = app.lectures.list(limit = 30)
                val deadlines = runCatching { app.lectures.deadlines() }.getOrNull()
                runCatching { app.courses.refresh() }
                deadlines?.let { app.syncReminders(it) }
                lectures to deadlines
            }
            result.onSuccess { (lectures, deadlines) ->
                _state.update {
                    it.copy(
                        lectures = lectures,
                        deadlines = deadlines?.let(::upcoming) ?: it.deadlines,
                        refreshing = false,
                        error = null,
                    )
                }
            }.onFailure { e -> _state.update { it.copy(refreshing = false, error = e.toErrorMessage()) } }
        }
    }

    /** Called while the screen is visible: keeps processing lectures up to date. */
    suspend fun pollWhileProcessing() {
        while (true) {
            delay(10_000)
            if (_state.value.lectures.orEmpty().any { it.isProcessing() }) refresh(quiet = true)
        }
    }

    fun uploadRecording(id: String) = viewModelScope.launch { app.uploads.enqueue(id, ignoreWifiOnly = false, replace = true) }

    fun uploadNow(id: String) = viewModelScope.launch { app.uploads.enqueue(id, ignoreWifiOnly = true) }

    fun deleteRecording(id: String) = viewModelScope.launch {
        app.uploads.cancel(id)
        app.recordings.delete(id)
    }

    fun dismissBatteryTip() = viewModelScope.launch { app.settings.dismissBatteryTip() }

    private fun upcoming(deadlines: List<DeadlineDto>): List<DeadlineDto> {
        val today = LocalDate.now()
        return deadlines
            .filter { d -> d.dueDate?.let { runCatching { !LocalDate.parse(it).isBefore(today) }.getOrDefault(true) } ?: false }
            .sortedBy { it.dueDate }
            .take(3)
    }

    private fun classAt(courses: List<CourseDto>, now: LocalDateTime): ClassInfo? {
        val slots = app.courses.slots(courses)
        if (slots.isEmpty()) return null
        val weekday = Routine.fromIsoDayOfWeek(now.dayOfWeek.value)
        val minute = now.hour * 60 + now.minute
        val byId = courses.associateBy { it.id }
        Routine.currentCourse(slots, weekday, minute)?.let { slot ->
            byId[slot.courseId]?.let { return ClassInfo(it, slot, isNow = true, daysAway = 0) }
        }
        val next = Routine.nextClass(slots, weekday, minute) ?: return null
        val course = byId[next.courseId] ?: return null
        var days = (next.weekday - weekday).mod(7)
        if (days == 0 && next.startMinutes <= minute) days = 7
        return ClassInfo(course, next, isNow = false, daysAway = days)
    }
}
