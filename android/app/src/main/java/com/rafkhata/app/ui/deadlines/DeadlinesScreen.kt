@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.deadlines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.rafkhata.app.data.api.DeadlineDto
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.data.settings.AppSettings
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.EmptyState
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.Fmt
import com.rafkhata.app.ui.common.LoadingBox
import com.rafkhata.app.ui.common.SectionTitle
import com.rafkhata.app.ui.common.currentLocale
import com.rafkhata.app.ui.nav.MainNavBar
import com.rafkhata.app.ui.nav.MainTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class DeadlinesViewModel(private val app: AppContainer) : ViewModel() {
    data class State(val deadlines: List<DeadlineDto>? = null, val refreshing: Boolean = false, val error: ErrorMessage? = null)

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    val settings: StateFlow<AppSettings> =
        app.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    init {
        viewModelScope.launch {
            app.lectures.cachedDeadlines()?.let { cached -> _state.update { it.copy(deadlines = cached) } }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            runCatching { app.lectures.deadlines() }
                .onSuccess { list ->
                    _state.update { it.copy(deadlines = list, refreshing = false, error = null) }
                    app.syncReminders(list)
                }
                .onFailure { e -> _state.update { it.copy(refreshing = false, error = e.toErrorMessage()) } }
        }
    }

    fun toggleMute(id: String, muted: Boolean) {
        viewModelScope.launch {
            app.settings.setDeadlineMuted(id, muted)
            _state.value.deadlines?.let { app.syncReminders(it) }
        }
    }
}

@Composable
fun deadlineKindLabel(kind: String): String = when (kind) {
    "ct" -> stringResource(R.string.kind_ct)
    "quiz" -> stringResource(R.string.kind_quiz)
    "assignment" -> stringResource(R.string.kind_assignment)
    "exam" -> stringResource(R.string.kind_exam)
    "presentation" -> stringResource(R.string.kind_presentation)
    "lab" -> stringResource(R.string.kind_lab)
    "class_change" -> stringResource(R.string.kind_class_change)
    else -> stringResource(R.string.kind_other)
}

@Composable
fun DeadlinesScreen(onOpenTab: (MainTab) -> Unit, onOpenLecture: (String) -> Unit) {
    val app = LocalContext.current.container
    val vm: DeadlinesViewModel = viewModel { DeadlinesViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_deadlines)) }) },
        bottomBar = { MainNavBar(MainTab.DEADLINES, onOpenTab) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            val deadlines = state.deadlines
            if (deadlines == null && state.error == null) {
                LoadingBox()
                return@PullToRefreshBox
            }
            val today = LocalDate.now()
            val dated = deadlines.orEmpty().mapNotNull { d -> Fmt.localDate(d.dueDate)?.let { d to it } }.sortedBy { it.second }
            val upcoming = dated.filter { !it.second.isBefore(today) }
            val past = dated.filter { it.second.isBefore(today) }.reversed()
            val undated = deadlines.orEmpty().filter { Fmt.localDate(it.dueDate) == null }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                state.error?.let { error -> item { ErrorBanner(error, onRetry = vm::refresh) } }
                if (deadlines != null && deadlines.isEmpty()) {
                    item { EmptyState(Icons.Filled.Event, stringResource(R.string.no_deadlines)) }
                }
                if (!settings.deadlineReminders && upcoming.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.reminders_off_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (upcoming.isNotEmpty()) item { SectionTitle(stringResource(R.string.upcoming)) }
                items(upcoming, key = { it.first.id }) { (d, date) ->
                    DeadlineRow(
                        deadline = d,
                        date = date,
                        today = today,
                        muted = d.id in settings.mutedDeadlines,
                        remindersOn = settings.deadlineReminders,
                        onToggleMute = { vm.toggleMute(d.id, d.id !in settings.mutedDeadlines) },
                        onClick = { onOpenLecture(d.lectureId) },
                    )
                }
                if (undated.isNotEmpty()) item { SectionTitle(stringResource(R.string.date_not_clear)) }
                items(undated, key = { it.id }) { d ->
                    DeadlineRow(d, null, today, muted = true, remindersOn = false, onToggleMute = {}, onClick = { onOpenLecture(d.lectureId) })
                }
                if (past.isNotEmpty()) item { SectionTitle(stringResource(R.string.past)) }
                items(past, key = { it.first.id }) { (d, date) ->
                    DeadlineRow(d, date, today, muted = true, remindersOn = false, onToggleMute = {}, onClick = { onOpenLecture(d.lectureId) })
                }
            }
        }
    }
}

@Composable
private fun DeadlineRow(
    deadline: DeadlineDto,
    date: LocalDate?,
    today: LocalDate,
    muted: Boolean,
    remindersOn: Boolean,
    onToggleMute: () -> Unit,
    onClick: () -> Unit,
) {
    val locale = currentLocale()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${deadlineKindLabel(deadline.kind)} · ${deadline.title}",
                style = MaterialTheme.typography.bodyLarge,
            )
            val whenText = date?.let {
                val days = ChronoUnit.DAYS.between(today, it).toInt()
                val relative = when {
                    days == 0 -> stringResource(R.string.due_today)
                    days == 1 -> stringResource(R.string.due_tomorrow)
                    days > 1 -> stringResource(R.string.due_in_days, days)
                    else -> null
                }
                listOfNotNull(Fmt.date(it, locale), relative).joinToString(" · ")
            } ?: deadline.dueText
            Text(
                listOfNotNull(deadline.courseTitle, whenText).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (date != null && !date.isBefore(today) && remindersOn) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    if (muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                    stringResource(if (muted) R.string.reminder_off else R.string.reminder_on),
                )
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
