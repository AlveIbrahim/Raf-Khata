@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.lecture

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.ui.common.BackButton
import com.rafkhata.app.ui.common.ConfirmDialog
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.Fmt
import com.rafkhata.app.ui.common.WhileResumed

@Composable
fun LectureScreen(lectureId: String, initialTab: Int, seekToMs: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val vm: LectureViewModel = viewModel { LectureViewModel(app, lectureId, seekToMs) }
    val state by vm.state.collectAsStateWithLifecycle()
    val player by vm.playerUi.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val holder = remember { WebViewHolder() }
    val snackbar = remember { SnackbarHostState() }
    val lecture = state.lecture
    val readyNotes = state.notes.let { if (it is Content.Ready) it.value else null }
    val messageText = state.message?.let { stringResource(it) }

    WhileResumed(Unit) { vm.poll() }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            vm.messageShown()
            snackbar.showSnackbar(messageText)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            lecture?.title?.ifBlank { null } ?: lecture?.courseTitle ?: stringResource(R.string.untitled_lecture),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = if (lecture != null && lecture.title.isNotBlank()) lecture.courseTitle else null
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    if (readyNotes != null) {
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, readyNotes.content.title)
                                .putExtra(Intent.EXTRA_TEXT, readyNotes.markdown)
                            context.startActivity(Intent.createChooser(send, null))
                        }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
                        IconButton(onClick = {
                            val webView = holder.webView
                            if (tab == 0 && webView != null) printNotes(context, webView, readyNotes.content.title) else tab = 0
                        }) { Icon(Icons.Filled.PictureAsPdf, stringResource(R.string.export_pdf)) }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rate_notes)) }, onClick = {
                            menu = false
                            dialog = "rate"
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.report_problem)) }, onClick = {
                            menu = false
                            dialog = "report"
                        })
                        if (lecture?.isMine == true) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete_lecture)) }, onClick = {
                                menu = false
                                dialog = "delete"
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (lecture?.status == "ready") {
                PlayerBar(
                    player = player,
                    onToggle = vm::togglePlay,
                    onSeekFraction = vm::seekToFraction,
                    onSpeed = vm::cycleSpeed,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.lectureError?.let { if (lecture == null) ErrorBanner(it, onRetry = null) }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(R.string.tab_notes, R.string.tab_transcript, R.string.tab_study).forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
                }
            }
            when (tab) {
                0 -> NotesTab(
                    lecture = lecture,
                    lang = state.lang,
                    notes = state.notes,
                    canRetry = lecture?.isMine == true,
                    holder = holder,
                    onLang = vm::setLang,
                    onGenerate = vm::generate,
                    onRetryProcessing = vm::retryProcessing,
                    onSeek = { vm.seekTo(it) },
                )
                1 -> TranscriptTab(
                    transcript = state.transcript,
                    positionMs = player.positionMs,
                    initialSeekMs = seekToMs,
                    onSeek = { vm.seekTo(it) },
                    onEdit = vm::editSegment,
                )
                else -> StudyTab(
                    study = state.study,
                    lang = state.lang,
                    lectureReady = lecture?.status == "ready",
                    onGenerate = vm::generate,
                )
            }
        }
    }

    when (dialog) {
        "rate" -> RateDialog(onSend = { rating, comment ->
            dialog = null
            vm.sendFeedback("rating", rating, comment)
        }, onDismiss = { dialog = null })
        "report" -> ReportDialog(onSend = { comment ->
            dialog = null
            vm.sendFeedback("report", null, comment)
        }, onDismiss = { dialog = null })
        "delete" -> ConfirmDialog(
            title = stringResource(R.string.delete_lecture),
            text = stringResource(R.string.delete_lecture_text),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = {
                dialog = null
                vm.delete()
            },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun PlayerBar(player: PlayerUi, onToggle: () -> Unit, onSeekFraction: (Float) -> Unit, onSpeed: () -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = if (player.durationMs > 0) player.positionMs.toFloat() / player.durationMs else 0f
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle) {
                if (player.loading && !player.playing) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Icon(
                        if (player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (player.playing) R.string.pause else R.string.play),
                    )
                }
            }
            Slider(
                value = dragging ?: fraction.coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let(onSeekFraction)
                    dragging = null
                },
                enabled = player.loaded && player.durationMs > 0,
                modifier = Modifier.weight(1f),
            )
            Text(
                Fmt.duration(((dragging?.times(player.durationMs)) ?: player.positionMs.toFloat()) / 1000.0),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            TextButton(onClick = onSpeed) { Text(speedLabel(player.speed)) }
        }
    }
}

private fun speedLabel(speed: Float): String =
    (if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()) + "×"

@Composable
private fun RateDialog(onSend: (Int, String) -> Unit, onDismiss: () -> Unit) {
    var rating by rememberSaveable { mutableIntStateOf(0) }
    var comment by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rate_notes)) },
        text = {
            Column {
                Row {
                    (1..5).forEach { n ->
                        IconButton(onClick = { rating = n }) {
                            Icon(
                                if (n <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = n.toString(),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    comment,
                    { comment = it },
                    label = { Text(stringResource(R.string.comment_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(rating, comment) }, enabled = rating > 0) { Text(stringResource(R.string.send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ReportDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var comment by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_problem)) },
        text = {
            Column {
                Text(stringResource(R.string.report_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(comment, { comment = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(comment) }, enabled = comment.isNotBlank()) { Text(stringResource(R.string.send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
