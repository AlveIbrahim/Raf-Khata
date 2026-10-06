@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.rafkhata.app.ui.courses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.LectureDto
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.BackButton
import com.rafkhata.app.ui.common.ConfirmDialog
import com.rafkhata.app.ui.common.EmptyState
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.Fmt
import com.rafkhata.app.ui.common.LectureRow
import com.rafkhata.app.ui.common.LoadingBox
import com.rafkhata.app.ui.common.SectionTitle
import com.rafkhata.app.ui.common.currentLocale
import com.rafkhata.app.ui.common.notesLangLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CourseViewModel(private val app: AppContainer, private val courseId: String) : ViewModel() {
    data class State(
        val course: CourseDto? = null,
        val lectures: List<LectureDto>? = null,
        val error: ErrorMessage? = null,
        val busy: Boolean = false,
        val deleted: Boolean = false,
    )

    private val _state = MutableStateFlow(State(course = app.courses.local(courseId)))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.lectures.cachedList(courseId)?.let { cached -> _state.update { it.copy(lectures = cached) } }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val course = app.courses.get(courseId)
                val lectures = app.lectures.list(courseId)
                course to lectures
            }.onSuccess { (course, lectures) -> _state.update { it.copy(course = course, lectures = lectures, error = null) } }
                .onFailure { e -> _state.update { it.copy(error = e.toErrorMessage()) } }
        }
    }

    fun confirmConsent() = act {
        val course = app.courses.confirmConsent(courseId)
        _state.update { it.copy(course = course) }
    }

    fun setArchived(archived: Boolean) = act {
        val course = app.courses.setArchived(courseId, archived)
        _state.update { it.copy(course = course) }
    }

    fun delete() = act {
        app.courses.delete(courseId)
        _state.update { it.copy(deleted = true) }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { block() }.onFailure { e -> _state.update { it.copy(error = e.toErrorMessage()) } }
            _state.update { it.copy(busy = false) }
        }
    }
}

@Composable
fun CourseScreen(
    courseId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onRecord: () -> Unit,
    onOpenLecture: (String) -> Unit,
) {
    val app = LocalContext.current.container
    val vm: CourseViewModel = viewModel { CourseViewModel(app, courseId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val course = state.course

    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(course?.title.orEmpty()) },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (course?.canEdit == true) {
                        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                    }
                    if (course != null) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (course.canEdit) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (course.archived) R.string.unarchive else R.string.archive)) },
                                    onClick = {
                                        menuOpen = false
                                        vm.setArchived(!course.archived)
                                    },
                                )
                            }
                            if (course.isOwner) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_course)) },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (course != null && !course.archived) {
                ExtendedFloatingActionButton(
                    onClick = onRecord,
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    text = { Text(stringResource(R.string.record)) },
                )
            }
        },
    ) { padding ->
        if (course == null) {
            if (state.error != null) {
                Column(Modifier.padding(padding)) { ErrorBanner(state.error!!, onRetry = vm::refresh) }
            } else {
                LoadingBox(Modifier.padding(padding))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            state.error?.let { error -> item { ErrorBanner(error, onRetry = vm::refresh) } }
            item { CourseHeader(course, busy = state.busy, onConfirmConsent = vm::confirmConsent) }
            item { SectionTitle(stringResource(R.string.lectures)) }
            val lectures = state.lectures
            when {
                lectures == null -> item { LoadingBox(Modifier.padding(16.dp)) }
                lectures.isEmpty() -> item { EmptyState(Icons.Filled.School, stringResource(R.string.no_lectures_in_course)) }
                else -> items(lectures, key = { it.id }) { lecture ->
                    LectureRow(lecture, onClick = { onOpenLecture(lecture.id) })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_course),
            text = stringResource(R.string.delete_course_text),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun CourseHeader(course: CourseDto, busy: Boolean, onConfirmConsent: () -> Unit) {
    val locale = currentLocale()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val facts = listOfNotNull(
            course.code?.takeIf { it.isNotBlank() },
            course.teacherName?.takeIf { it.isNotBlank() },
            course.semester?.takeIf { it.isNotBlank() },
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
        Text(
            course.spaceName?.let { stringResource(R.string.shared_with_section, it) } ?: stringResource(R.string.only_me),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        course.notesLang?.let {
            Text(
                stringResource(R.string.notes_in, notesLangLabel(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (course.routine.isNotEmpty()) {
            Text(stringResource(R.string.routine), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            course.routine.sortedWith(compareBy({ Fmt.WEEK_ORDER.indexOf(it.weekday) }, { it.startTime })).forEach { slot ->
                Text(
                    listOfNotNull(
                        Fmt.weekday(slot.weekday, locale),
                        "${Fmt.time(slot.startTime, locale)} – ${Fmt.time(slot.endTime, locale)}",
                        slot.room?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (course.glossary.isNotEmpty()) {
            Text(stringResource(R.string.glossary), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                course.glossary.take(40).forEach { term -> AssistChip(onClick = {}, label = { Text(term) }) }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (course.consentConfirmed) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        stringResource(if (course.consentConfirmed) R.string.consent_confirmed else R.string.consent_needed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!course.consentConfirmed) {
                    FilledTonalButton(onClick = onConfirmConsent, enabled = !busy) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }
}
