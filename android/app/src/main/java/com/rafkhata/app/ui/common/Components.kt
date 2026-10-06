package com.rafkhata.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.rafkhata.app.R
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.LectureDto
import kotlinx.coroutines.CoroutineScope

@Composable
fun ErrorMessage.text(): String {
    val base = stringResource(res)
    return if (detail.isNullOrBlank()) base else "$base: $detail"
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/** A slim banner for errors when older (cached) content is still on screen. */
@Composable
fun ErrorBanner(error: ErrorMessage, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                error.text(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Localized label for a lecture's processing state. */
@Composable
fun lectureStatusText(lecture: LectureDto): String = when (lecture.status) {
    "created", "uploading" -> stringResource(R.string.status_uploading)
    "queued" -> stringResource(R.string.status_queued)
    "processing" -> when (lecture.statusDetail) {
        "assemble" -> stringResource(R.string.step_assemble)
        "transcribe" -> stringResource(R.string.step_transcribe)
        "correct" -> stringResource(R.string.step_correct)
        "notes" -> stringResource(R.string.step_notes)
        "study" -> stringResource(R.string.step_study)
        else -> stringResource(R.string.status_processing)
    }
    "ready" -> stringResource(R.string.status_ready)
    "failed" -> stringResource(R.string.status_failed)
    else -> lecture.status
}

fun LectureDto.isProcessing(): Boolean = status in setOf("created", "uploading", "queued", "processing")

@Composable
fun LectureRow(lecture: LectureDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            lecture.title.ifBlank { lecture.courseTitle ?: stringResource(R.string.untitled_lecture) },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            lecture.courseTitle?.takeIf { lecture.title.isNotBlank() },
            Fmt.dateTime(lecture.startedAt ?: lecture.createdAt, locale).ifBlank { null },
            if (lecture.durationS > 0) Fmt.duration(lecture.durationS) else null,
            if (!lecture.isMine && lecture.recordedByName.isNotBlank()) {
                stringResource(R.string.recorded_by, lecture.recordedByName)
            } else {
                null
            },
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lecture.status != "ready") {
            val color = if (lecture.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Text(lectureStatusText(lecture), style = MaterialTheme.typography.labelMedium, color = color)
            if (lecture.isProcessing() && lecture.progress > 0) {
                LinearProgressIndicator(
                    progress = { lecture.progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

/** Runs [block] while the screen is resumed, e.g. to poll processing status. */
@Composable
fun WhileResumed(key: Any?, block: suspend CoroutineScope.() -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, key) { owner.repeatOnLifecycle(Lifecycle.State.RESUMED, block) }
}
