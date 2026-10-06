@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.record

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.CourseDto
import com.rafkhata.app.recording.RecorderError
import com.rafkhata.app.recording.RecorderStatus
import com.rafkhata.app.recording.RecorderUi
import com.rafkhata.app.ui.common.BackButton
import com.rafkhata.app.ui.common.ConfirmDialog
import com.rafkhata.app.ui.common.Fmt
import com.rafkhata.core.SoundCheck
import com.rafkhata.core.SoundTip

@Composable
fun RecordScreen(courseId: String?, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val vm: RecordViewModel = viewModel { RecordViewModel(app, courseId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val recorder by vm.recorder.collectAsStateWithLifecycle()
    val courses by vm.courses.collectAsStateWithLifecycle()
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var cameraMissing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true || hasMicPermission(context)) {
            permissionDenied = false
            pendingAction?.invoke()
        } else {
            permissionDenied = true
        }
        pendingAction = null
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> vm.onPhotoResult(saved) }

    fun withMic(action: () -> Unit) {
        if (hasMicPermission(context)) {
            action()
        } else {
            pendingAction = action
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    LaunchedEffect(recorder.savedRecordingId) {
        if (recorder.savedRecordingId != null && recorder.error == null) {
            vm.clearRecorderError()
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.record_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            recorder.error?.let { error ->
                ErrorCard(
                    error = error,
                    saved = recorder.savedRecordingId != null,
                    onOpenSettings = { openAppSettings(context) },
                    onDismiss = {
                        vm.clearRecorderError()
                        if (recorder.savedRecordingId != null) onSaved()
                    },
                )
            }
            if (permissionDenied) {
                ErrorCard(RecorderError.NO_PERMISSION, saved = false, onOpenSettings = { openAppSettings(context) }, onDismiss = {
                    permissionDenied = false
                })
            }

            if (recorder.active) {
                RecordingPanel(
                    recorder = recorder,
                    onPause = vm::pause,
                    onResume = vm::resume,
                    onStop = { confirmStop = true },
                    onBookmark = vm::bookmark,
                    onPhoto = {
                        val uri: Uri? = vm.preparePhoto()
                        if (uri != null) {
                            try {
                                cameraLauncher.launch(uri)
                            } catch (_: ActivityNotFoundException) {
                                vm.onPhotoResult(false)
                                cameraMissing = true
                            }
                        }
                    },
                )
                if (cameraMissing) Text(stringResource(R.string.no_camera_app), color = MaterialTheme.colorScheme.error)
            } else {
                SetupPanel(
                    state = state,
                    courses = courses.orEmpty().filterNot { it.archived },
                    selected = vm.course(),
                    canStart = vm.canStart() && recorder.status == RecorderStatus.IDLE,
                    onSelectCourse = vm::selectCourse,
                    onTitle = vm::setTitle,
                    onConsent = vm::setConsent,
                    onSoundCheck = { withMic { vm.runSoundCheck() } },
                    onStart = { withMic { vm.start() } },
                )
            }
        }
    }

    if (confirmStop) {
        ConfirmDialog(
            title = stringResource(R.string.stop_recording_title),
            text = stringResource(R.string.stop_recording_text),
            confirmLabel = stringResource(R.string.stop),
            onConfirm = {
                confirmStop = false
                vm.stop()
            },
            onDismiss = { confirmStop = false },
        )
    }
}

@Composable
private fun SetupPanel(
    state: RecordViewModel.State,
    courses: List<CourseDto>,
    selected: CourseDto?,
    canStart: Boolean,
    onSelectCourse: (String?) -> Unit,
    onTitle: (String) -> Unit,
    onConsent: (Boolean) -> Unit,
    onSoundCheck: () -> Unit,
    onStart: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Text(stringResource(R.string.course), style = MaterialTheme.typography.titleSmall)
    Box {
        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.title ?: stringResource(R.string.no_course))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            courses.forEach { course ->
                DropdownMenuItem(text = { Text(course.title) }, onClick = {
                    menu = false
                    onSelectCourse(course.id)
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.no_course)) }, onClick = {
                menu = false
                onSelectCourse(null)
            })
        }
    }
    OutlinedTextField(
        value = state.title,
        onValueChange = onTitle,
        label = { Text(stringResource(R.string.lecture_topic_optional)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (selected?.consentConfirmed == true) {
        Text(
            stringResource(R.string.consent_already_confirmed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    } else {
        Row(
            Modifier.fillMaxWidth().clickable { onConsent(!state.consentChecked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.consentChecked, onCheckedChange = onConsent)
            Text(stringResource(R.string.consent_checkbox), style = MaterialTheme.typography.bodyMedium)
        }
    }

    SoundCheckCard(state, onSoundCheck)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.placement_tips_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.placement_tips), style = MaterialTheme.typography.bodyMedium)
        }
    }

    Button(
        onClick = onStart,
        enabled = canStart,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.start_recording), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SoundCheckCard(state: RecordViewModel.State, onSoundCheck: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null)
                Text(
                    stringResource(R.string.sound_check),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                TextButton(onClick = onSoundCheck, enabled = !state.soundChecking) {
                    Text(stringResource(if (state.soundCheck == null) R.string.sound_check_run else R.string.sound_check_again))
                }
            }
            if (state.soundChecking) {
                Text(stringResource(R.string.sound_check_listening), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { state.soundLevel }, modifier = Modifier.fillMaxWidth())
            } else if (state.soundCheckFailed) {
                Text(stringResource(R.string.error_mic_unavailable), color = MaterialTheme.colorScheme.error)
            } else {
                state.soundCheck?.let { SoundCheckResult(it) } ?: Text(
                    stringResource(R.string.sound_check_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SoundCheckResult(check: SoundCheck) {
    if (check.good) {
        Text(stringResource(R.string.sound_good), color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
    }
    check.tips.forEach { tip ->
        val text = when (tip) {
            SoundTip.TOO_QUIET -> stringResource(R.string.sound_tip_quiet)
            SoundTip.NOISY -> stringResource(R.string.sound_tip_noisy)
            SoundTip.CLIPPING -> stringResource(R.string.sound_tip_clipping)
        }
        Text("• $text", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecordingPanel(
    recorder: RecorderUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onBookmark: (String) -> Unit,
    onPhoto: () -> Unit,
) {
    val paused = recorder.status == RecorderStatus.PAUSED
    val ready = recorder.status == RecorderStatus.RECORDING || paused
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        recorder.courseTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
        Text(
            when (recorder.status) {
                RecorderStatus.STARTING -> stringResource(R.string.recording_starting)
                RecorderStatus.PAUSED -> stringResource(R.string.recording_paused)
                RecorderStatus.STOPPING -> stringResource(R.string.recording_saving)
                else -> stringResource(R.string.recording_in_progress)
            },
            color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(Fmt.duration(recorder.elapsedS), fontSize = 56.sp, fontWeight = FontWeight.Light)
        LinearProgressIndicator(
            progress = { if (paused) 0f else recorder.level },
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
        if (recorder.silenced) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MicOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.mic_silenced), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
            }
        } else if (recorder.clipping) {
            Text(stringResource(R.string.sound_tip_clipping), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onBookmark("important") }, enabled = ready, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.bookmark_important), maxLines = 1)
            }
            FilledTonalButton(onClick = { onBookmark("confused") }, enabled = ready, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.bookmark_confused), maxLines = 1)
            }
        }
        OutlinedButton(onClick = onPhoto, enabled = ready, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.board_photo))
        }
        Text(
            pluralStringResource(R.plurals.bookmark_count, recorder.bookmarks, recorder.bookmarks) + " · " +
                pluralStringResource(R.plurals.photo_count, recorder.photos, recorder.photos),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = if (paused) onResume else onPause, enabled = ready, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (paused) R.string.resume else R.string.pause))
            }
            Button(
                onClick = onStop,
                enabled = ready,
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.stop))
            }
        }
        Text(
            stringResource(R.string.screen_off_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorCard(error: RecorderError, saved: Boolean, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(
                    when (error) {
                        RecorderError.NO_PERMISSION -> R.string.error_mic_permission
                        RecorderError.MIC_UNAVAILABLE -> R.string.error_mic_unavailable
                        RecorderError.START_NOT_ALLOWED -> R.string.error_start_not_allowed
                        RecorderError.FAILED -> R.string.error_recording_failed
                    },
                ),
            )
            if (saved) Text(stringResource(R.string.recording_partly_saved), style = MaterialTheme.typography.bodySmall)
            Row {
                if (error == RecorderError.NO_PERMISSION) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
