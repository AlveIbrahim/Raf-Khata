package com.rafkhata.app.ui.lecture

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rafkhata.app.R
import com.rafkhata.app.data.api.BookmarkDto
import com.rafkhata.app.data.api.PhotoDto
import com.rafkhata.app.data.api.TranscriptDto
import com.rafkhata.app.data.api.TranscriptSegmentDto
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.Fmt
import com.rafkhata.app.ui.common.LoadingBox

@Composable
fun TranscriptTab(
    transcript: Content<TranscriptDto>,
    positionMs: Long,
    initialSeekMs: Long,
    onSeek: (Double) -> Unit,
    onEdit: (String, String) -> Unit,
) {
    when (transcript) {
        Content.Loading -> LoadingBox()
        Content.Pending, Content.Missing -> CenteredMessage(stringResource(R.string.transcript_not_ready), busy = transcript == Content.Pending)
        is Content.Failed -> ErrorBanner(transcript.error, onRetry = null)
        is Content.Ready -> TranscriptList(transcript.value, positionMs, initialSeekMs, onSeek, onEdit)
    }
}

@Composable
private fun TranscriptList(
    transcript: TranscriptDto,
    positionMs: Long,
    initialSeekMs: Long,
    onSeek: (Double) -> Unit,
    onEdit: (String, String) -> Unit,
) {
    val segments = transcript.segments
    val listState = rememberLazyListState()
    val marks = remember(transcript) { bookmarksBySegment(segments, transcript.bookmarks) }
    val positionS = positionMs / 1000.0
    val activeIndex = remember(segments, positionMs) { segments.indexOfLast { it.start <= positionS } }
    var editing by remember { mutableStateOf<TranscriptSegmentDto?>(null) }
    var viewing by remember { mutableStateOf<PhotoDto?>(null) }
    val headerItems = if (transcript.photos.isNotEmpty()) 1 else 0

    LaunchedEffect(segments) {
        if (initialSeekMs >= 0) {
            val index = segments.indexOfLast { it.start * 1000 <= initialSeekMs }
            if (index >= 0) listState.scrollToItem(index + headerItems)
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (transcript.photos.isNotEmpty()) {
            item { PhotoStrip(transcript.photos, onOpen = { viewing = it }) }
        }
        itemsIndexed(segments, key = { _, s -> s.id }) { index, segment ->
            SegmentRow(
                segment = segment,
                active = index == activeIndex,
                marks = marks[segment.id].orEmpty(),
                canEdit = transcript.canEdit,
                onClick = { onSeek(segment.start) },
                onEdit = { editing = segment },
            )
        }
    }

    editing?.let { segment ->
        EditSegmentDialog(
            initial = segment.text,
            onSave = { text ->
                editing = null
                if (text.isNotBlank() && text != segment.text) onEdit(segment.id, text.trim())
            },
            onDismiss = { editing = null },
        )
    }
    viewing?.let { photo ->
        Dialog(onDismissRequest = { viewing = null }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                photo.url?.let { RemoteImage(it, maxPx = 2048, contentDescription = null, modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = {
                    viewing = null
                    onSeek(photo.tOffsetS)
                }) { Text(stringResource(R.string.play_from, Fmt.duration(photo.tOffsetS))) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentRow(
    segment: TranscriptSegmentDto,
    active: Boolean,
    marks: List<String>,
    canEdit: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    val background = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = if (canEdit) onEdit else null)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.width(56.dp)) {
            Text(Fmt.duration(segment.start), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            speakerLabel(segment.speaker)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                marks.forEach { kind ->
                    Icon(
                        when (kind) {
                            "important" -> Icons.Filled.Star
                            "confused" -> Icons.AutoMirrored.Filled.HelpOutline
                            else -> Icons.Filled.Bookmark
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            segment.text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (canEdit && active) {
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Edit, stringResource(R.string.edit), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun speakerLabel(speaker: String?): String? = when (speaker) {
    "T" -> stringResource(R.string.speaker_teacher)
    "S" -> stringResource(R.string.speaker_student)
    else -> null
}

@Composable
private fun PhotoStrip(photos: List<PhotoDto>, onOpen: (PhotoDto) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.board_photos),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos.filter { it.url != null }, key = { it.id }) { photo ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RemoteImage(
                        url = photo.url.orEmpty(),
                        maxPx = 320,
                        contentDescription = stringResource(R.string.board_photo),
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpen(photo) },
                    )
                    Text(Fmt.duration(photo.tOffsetS), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun EditSegmentDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_line)) },
        text = {
            Column {
                Text(stringResource(R.string.edit_line_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Puts each bookmark on the transcript line that was playing at that moment. */
private fun bookmarksBySegment(segments: List<TranscriptSegmentDto>, bookmarks: List<BookmarkDto>): Map<String, List<String>> {
    if (segments.isEmpty()) return emptyMap()
    val result = mutableMapOf<String, MutableList<String>>()
    for (bookmark in bookmarks) {
        val segment = segments.lastOrNull { it.start <= bookmark.tOffsetS } ?: segments.first()
        result.getOrPut(segment.id) { mutableListOf() }.add(bookmark.kind)
    }
    return result
}
