@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.courses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.EmptyState
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.LoadingBox
import com.rafkhata.app.ui.common.SectionTitle
import com.rafkhata.app.ui.nav.MainNavBar
import com.rafkhata.app.ui.nav.MainTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CoursesViewModel(private val app: AppContainer) : ViewModel() {
    data class State(val refreshing: Boolean = false, val error: ErrorMessage? = null)

    val courses = app.courses.courses
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.courses.cached()
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            runCatching { app.courses.refresh() }
                .onSuccess { _state.update { State() } }
                .onFailure { e -> _state.update { State(error = e.toErrorMessage()) } }
        }
    }
}

@Composable
fun CoursesScreen(onOpenTab: (MainTab) -> Unit, onOpenCourse: (String) -> Unit, onAddCourse: () -> Unit) {
    val app = LocalContext.current.container
    val vm: CoursesViewModel = viewModel { CoursesViewModel(app) }
    val courses by vm.courses.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var showArchived by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_courses)) }) },
        bottomBar = { MainNavBar(MainTab.COURSES, onOpenTab) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddCourse) { Icon(Icons.Filled.Add, stringResource(R.string.add_course)) }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val list = courses
            when {
                list == null && state.error == null -> LoadingBox()
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    state.error?.let { error -> item { ErrorBanner(error, onRetry = vm::refresh) } }
                    val active = list.orEmpty().filterNot { it.archived }
                    val archived = list.orEmpty().filter { it.archived }
                    if (list != null && active.isEmpty()) {
                        item {
                            EmptyState(Icons.AutoMirrored.Filled.MenuBook, stringResource(R.string.no_courses)) {
                                TextButton(onClick = onAddCourse) { Text(stringResource(R.string.add_course)) }
                            }
                        }
                    }
                    items(active, key = { it.id }) { course ->
                        CourseRow(course, onClick = { onOpenCourse(course.id) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                    if (archived.isNotEmpty()) {
                        item {
                            SectionTitle(stringResource(R.string.archived_courses, archived.size)) {
                                TextButton(onClick = { showArchived = !showArchived }) {
                                    Text(stringResource(if (showArchived) R.string.hide else R.string.show))
                                }
                            }
                        }
                        if (showArchived) {
                            items(archived, key = { it.id }) { course -> CourseRow(course, onClick = { onOpenCourse(course.id) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CourseRow(course: CourseDto, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            listOfNotNull(course.code?.takeIf { it.isNotBlank() }, course.title).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
        )
        val meta = listOfNotNull(
            course.teacherName?.takeIf { it.isNotBlank() },
            course.spaceName?.let { stringResource(R.string.shared_with_section, it) },
            pluralStringResource(R.plurals.lecture_count, course.lectureCount, course.lectureCount),
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
