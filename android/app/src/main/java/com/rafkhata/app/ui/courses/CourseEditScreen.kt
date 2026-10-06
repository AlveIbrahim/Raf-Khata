@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.courses

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.CourseDto
import com.rafkhata.app.data.api.CourseIn
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.RoutineSlotDto
import com.rafkhata.app.data.api.SpaceDto
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.BackButton
import com.rafkhata.app.ui.common.Fmt
import com.rafkhata.app.ui.common.LoadingBox
import com.rafkhata.app.ui.common.NotesLangPicker
import com.rafkhata.app.ui.common.currentLocale
import com.rafkhata.app.ui.common.text
import com.rafkhata.core.Slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class SlotForm(val key: Long, val weekday: Int, val start: String, val end: String, val room: String) {
    val valid: Boolean get() = runCatching { Slot.minutesOf(end) > Slot.minutesOf(start) }.getOrDefault(false)
}

class CourseEditViewModel(private val app: AppContainer, private val courseId: String?) : ViewModel() {
    data class State(
        val loading: Boolean = false,
        val title: String = "",
        val code: String = "",
        val teacher: String = "",
        val semester: String = "",
        val spaceId: String? = null,
        val notesLang: String? = null,
        val glossary: String = "",
        val slots: List<SlotForm> = emptyList(),
        val spaces: List<SpaceDto> = emptyList(),
        val saving: Boolean = false,
        val error: ErrorMessage? = null,
        val savedId: String? = null,
    ) {
        val canSave: Boolean get() = title.isNotBlank() && slots.all { it.valid } && !saving
    }

    private var nextKey = 0L
    private val _state = MutableStateFlow(State(loading = courseId != null))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.spaces.cached()?.let { cached -> _state.update { it.copy(spaces = cached) } }
            runCatching { app.spaces.list() }.onSuccess { list -> _state.update { it.copy(spaces = list) } }
        }
        if (courseId != null) {
            viewModelScope.launch {
                val course = app.courses.local(courseId) ?: runCatching { app.courses.get(courseId) }
                    .onFailure { e -> _state.update { it.copy(loading = false, error = e.toErrorMessage()) } }
                    .getOrNull()
                if (course != null) fill(course)
            }
        }
    }

    private fun fill(course: CourseDto) = _state.update {
        it.copy(
            loading = false,
            title = course.title,
            code = course.code.orEmpty(),
            teacher = course.teacherName.orEmpty(),
            semester = course.semester.orEmpty(),
            spaceId = course.spaceId,
            notesLang = course.notesLang,
            glossary = course.glossary.joinToString(", "),
            slots = course.routine.map { r -> SlotForm(nextKey++, r.weekday, r.startTime, r.endTime, r.room.orEmpty()) },
        )
    }

    fun edit(transform: (State) -> State) = _state.update { transform(it).copy(error = null) }

    fun addSlot() = _state.update {
        val last = it.slots.lastOrNull()
        val slot = SlotForm(nextKey++, last?.weekday ?: 6, last?.start ?: "09:00", last?.end ?: "10:00", last?.room.orEmpty())
        it.copy(slots = it.slots + slot)
    }

    fun updateSlot(slot: SlotForm) = _state.update { s -> s.copy(slots = s.slots.map { if (it.key == slot.key) slot else it }) }

    fun removeSlot(key: Long) = _state.update { s -> s.copy(slots = s.slots.filterNot { it.key == key }) }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val input = CourseIn(
            title = s.title.trim(),
            code = s.code.trim().ifEmpty { null },
            teacherName = s.teacher.trim().ifEmpty { null },
            semester = s.semester.trim().ifEmpty { null },
            spaceId = s.spaceId,
            glossary = s.glossary.split(',', '\n', '،').map { it.trim() }.filter { it.isNotEmpty() },
            notesLang = s.notesLang,
            routine = s.slots.map { RoutineSlotDto(it.weekday, it.start, it.end, it.room.trim().ifEmpty { null }) },
        )
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching { if (courseId == null) app.courses.create(input) else app.courses.update(courseId, input) }
                .onSuccess { course -> _state.update { it.copy(saving = false, savedId = course.id) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.toErrorMessage()) } }
        }
    }
}

@Composable
fun CourseEditScreen(courseId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val app = LocalContext.current.container
    val vm: CourseEditViewModel = viewModel { CourseEditViewModel(app, courseId) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (courseId == null) R.string.add_course else R.string.edit_course)) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = vm::save, enabled = state.canSave) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextInput(state.title, R.string.course_title) { v -> vm.edit { it.copy(title = v) } }
            TextInput(state.code, R.string.course_code) { v -> vm.edit { it.copy(code = v) } }
            TextInput(state.teacher, R.string.teacher_name) { v -> vm.edit { it.copy(teacher = v) } }
            TextInput(state.semester, R.string.semester) { v -> vm.edit { it.copy(semester = v) } }

            SpacePicker(state.spaces, state.spaceId) { id -> vm.edit { it.copy(spaceId = id) } }

            Text(stringResource(R.string.routine), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                stringResource(R.string.routine_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.slots.forEach { slot ->
                SlotEditor(slot, onChange = vm::updateSlot, onRemove = { vm.removeSlot(slot.key) })
            }
            OutlinedButton(onClick = vm::addSlot) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.add_class_time), modifier = Modifier.padding(start = 8.dp))
            }

            OutlinedTextField(
                value = state.glossary,
                onValueChange = { v -> vm.edit { it.copy(glossary = v) } },
                label = { Text(stringResource(R.string.glossary)) },
                supportingText = { Text(stringResource(R.string.glossary_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Text(stringResource(R.string.notes_language), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            NotesLangPicker(selected = state.notesLang, onSelect = { lang -> vm.edit { it.copy(notesLang = lang) } }, allowDefault = true)

            state.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
            Button(onClick = vm::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save))
            }
        }
    }
}

@Composable
private fun TextInput(value: String, label: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SpacePicker(spaces: List<SpaceDto>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = spaces.firstOrNull { it.id == selected }?.name ?: stringResource(R.string.only_me)
    Column {
        Text(stringResource(R.string.share_with), style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.only_me)) }, onClick = {
                    open = false
                    onSelect(null)
                })
                spaces.forEach { space ->
                    DropdownMenuItem(text = { Text(space.name) }, onClick = {
                        open = false
                        onSelect(space.id)
                    })
                }
            }
        }
        Text(
            stringResource(R.string.share_with_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SlotEditor(slot: SlotForm, onChange: (SlotForm) -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val locale = currentLocale()
    var dayMenu by remember { mutableStateOf(false) }

    fun pickTime(current: String, onPicked: (String) -> Unit) {
        val minutes = runCatching { Slot.minutesOf(current) }.getOrDefault(9 * 60)
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(String.format(Locale.ROOT, "%02d:%02d", hour, minute)) },
            minutes / 60,
            minutes % 60,
            DateFormat.is24HourFormat(context),
        ).show()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { dayMenu = true }) { Text(Fmt.weekday(slot.weekday, locale)) }
                    DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }) {
                        Fmt.WEEK_ORDER.forEach { day ->
                            DropdownMenuItem(text = { Text(Fmt.weekday(day, locale)) }, onClick = {
                                dayMenu = false
                                onChange(slot.copy(weekday = day))
                            })
                        }
                    }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickTime(slot.start) { onChange(slot.copy(start = it)) } }) {
                    Text(Fmt.time(slot.start, locale))
                }
                Text("–")
                OutlinedButton(onClick = { pickTime(slot.end) { onChange(slot.copy(end = it)) } }) {
                    Text(Fmt.time(slot.end, locale))
                }
            }
            if (!slot.valid) {
                Text(stringResource(R.string.end_after_start), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = slot.room,
                onValueChange = { onChange(slot.copy(room = it)) },
                label = { Text(stringResource(R.string.room)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp),
            )
        }
    }
}
