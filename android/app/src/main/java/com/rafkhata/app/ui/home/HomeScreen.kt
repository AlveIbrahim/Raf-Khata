@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.home

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.DeadlineDto
import com.rafkhata.app.data.db.RecordingEntity
import com.rafkhata.app.data.db.RecordingState
import com.rafkhata.app.recording.RecorderStatus
import com.rafkhata.app.recording.RecorderUi
import com.rafkhata.app.ui.common.ConfirmDialog
import com.rafkhata.app.ui.common.EmptyState
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.Fmt
import com.rafkhata.app.ui.common.LectureRow
import com.rafkhata.app.ui.common.SectionTitle
import com.rafkhata.app.ui.common.WhileResumed
import com.rafkhata.app.ui.common.currentLocale
import com.rafkhata.app.ui.deadlines.deadlineKindLabel
import com.rafkhata.app.ui.nav.MainNavBar
import com.rafkhata.app.ui.nav.MainTab

@Composable
fun HomeScreen(
    onOpenTab: (MainTab) -> Unit,
    onRecord: (courseId: String?) -> Unit,
    onOpenLecture: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.container
    val vm: HomeViewModel = viewModel { HomeViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val recorder by vm.recorder.collectAsStateWithLifecycle()
    val classInfo by vm.classInfo.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<RecordingEntity?>(null) }

    WhileResumed(Unit) { vm.pollWhileProcessing() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, stringResource(R.string.search)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                },
            )
        },
        bottomBar = { MainNavBar(MainTab.HOME, onOpenTab) },
        floatingActionButton = {
            if (!recorder.active) {
                ExtendedFloatingActionButton(
                    onClick = { onRecord(classInfo?.takeIf { it.isNow }?.course?.id) },
                    icon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    text = { Text(stringResource(R.string.record)) },
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                state.error?.let { error -> item { ErrorBanner(error, onRetry = { vm.refresh() }) } }

                if (recorder.active) {
                    item { RecordingNowCard(recorder, onOpen = { onRecord(null) }) }
                }

                val interrupted = recordings.filter { it.state == RecordingState.INTERRUPTED }
                items(interrupted, key = { "int-" + it.id }) { rec ->
                    InterruptedCard(rec, onUpload = { vm.uploadRecording(rec.id) }, onDelete = { confirmDelete = rec })
                }

                if (!settings.dismissedBatteryTip && !isIgnoringBatteryOptimizations(context)) {
                    item { BatteryTipCard(onOpenSettings = { openBatterySettings(context) }, onDismiss = { vm.dismissBatteryTip() }) }
                }

                classInfo?.let { info ->
                    item { ClassCard(info, recorderActive = recorder.active, onRecord = { onRecord(info.course.id) }) }
                }

                val uploads = recordings.filter { it.state != RecordingState.INTERRUPTED && it.state != RecordingState.RECORDING }
                if (uploads.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.uploads)) }
                    items(uploads, key = { "up-" + it.id }) { rec ->
                        UploadRow(
                            rec,
                            wifiOnly = settings.wifiOnlyUploads,
                            onUploadNow = { vm.uploadNow(rec.id) },
                            onDelete = { confirmDelete = rec },
                        )
                    }
                }

                if (state.deadlines.isNotEmpty()) {
                    item {
                        SectionTitle(stringResource(R.string.upcoming_deadlines)) {
                            TextButton(onClick = { onOpenTab(MainTab.DEADLINES) }) { Text(stringResource(R.string.see_all)) }
                        }
                    }
                    items(state.deadlines, key = { "dl-" + it.id }) { d -> DeadlineLine(d, onClick = { onOpenLecture(d.lectureId) }) }
                }

                item { SectionTitle(stringResource(R.string.recent_lectures)) }
                val lectures = state.lectures
                when {
                    lectures == null -> item {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    }
                    lectures.isEmpty() -> item {
                        EmptyState(Icons.Filled.School, stringResource(R.string.no_lectures_yet))
                    }
                    else -> items(lectures, key = { it.id }) { lecture ->
                        LectureRow(lecture, onClick = { onOpenLecture(lecture.id) })
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }

    confirmDelete?.let { rec ->
        ConfirmDialog(
            title = stringResource(R.string.delete_recording_title),
            text = stringResource(R.string.delete_recording_text),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = {
                vm.deleteRecording(rec.id)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun RecordingNowCard(recorder: RecorderUi, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (recorder.status == RecorderStatus.PAUSED) R.string.recording_paused else R.string.recording_in_progress),
                    style = MaterialTheme.typography.titleMedium,
                )
                recorder.courseTitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Text(Fmt.duration(recorder.elapsedS), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun InterruptedCard(rec: RecordingEntity, onUpload: () -> Unit, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.interrupted_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.interrupted_text, rec.courseTitle ?: rec.title, Fmt.duration(rec.durationS)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onUpload) { Text(stringResource(R.string.upload)) }
                OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
private fun BatteryTipCard(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.battery_tip_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.battery_tip_text), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

@Composable
private fun ClassCard(info: ClassInfo, recorderActive: Boolean, onRecord: () -> Unit) {
    val locale = currentLocale()
    val slot = info.slot
    val whenText = when {
        info.isNow -> stringResource(R.string.class_now)
        info.daysAway == 0 -> stringResource(R.string.class_today)
        info.daysAway == 1 -> stringResource(R.string.class_tomorrow)
        else -> Fmt.weekday(slot.weekday, locale)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (info.isNow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(whenText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(info.course.title, style = MaterialTheme.typography.titleMedium)
                val time = "${Fmt.time(slot.start, locale)} – ${Fmt.time(slot.end, locale)}"
                Text(
                    listOfNotNull(time, slot.room?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (info.isNow && !recorderActive) {
                FilledTonalButton(onClick = onRecord) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.record))
                }
            }
        }
    }
}

@Composable
private fun UploadRow(rec: RecordingEntity, wifiOnly: Boolean, onUploadNow: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(rec.courseTitle ?: rec.title, style = MaterialTheme.typography.titleSmall)
        val status = when (rec.state) {
            RecordingState.UPLOADING -> stringResource(R.string.upload_uploading, (rec.progress * 100).toInt())
            RecordingState.FAILED -> stringResource(R.string.upload_failed, rec.error.orEmpty())
            else -> stringResource(if (wifiOnly) R.string.upload_waiting_wifi else R.string.upload_waiting_network)
        }
        Text(
            "${Fmt.duration(rec.durationS)} · $status",
            style = MaterialTheme.typography.bodySmall,
            color = if (rec.state == RecordingState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rec.state == RecordingState.UPLOADING) {
            LinearProgressIndicator(progress = { rec.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (rec.state != RecordingState.UPLOADING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUploadNow) {
                    Text(stringResource(if (rec.state == RecordingState.FAILED) R.string.retry else R.string.upload_now))
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@Composable
private fun DeadlineLine(deadline: DeadlineDto, onClick: () -> Unit) {
    val locale = currentLocale()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${deadlineKindLabel(deadline.kind)} · ${deadline.title}", style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            deadline.courseTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Fmt.localDate(deadline.dueDate)?.let {
            Text(Fmt.date(it, locale), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        }
    }
    Spacer(Modifier.height(2.dp))
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
