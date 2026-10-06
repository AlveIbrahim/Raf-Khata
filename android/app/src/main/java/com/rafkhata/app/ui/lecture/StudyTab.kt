package com.rafkhata.app.ui.lecture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rafkhata.app.R
import com.rafkhata.app.data.api.FlashcardDto
import com.rafkhata.app.data.api.McqDto
import com.rafkhata.app.data.api.StudyDto
import com.rafkhata.app.data.api.WrittenQuestionDto
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.LoadingBox
import com.rafkhata.app.ui.common.notesLangLabel

private enum class StudyMode { FLASHCARDS, QUIZ, QUESTIONS }

@Composable
fun StudyTab(study: Content<StudyDto>, lang: String?, lectureReady: Boolean, onGenerate: (String) -> Unit) {
    when (study) {
        Content.Loading -> LoadingBox()
        Content.Pending -> CenteredMessage(stringResource(R.string.study_being_made), busy = true)
        Content.Missing -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.study_missing), textAlign = TextAlign.Center)
            if (lang != null && lectureReady) {
                Button(onClick = { onGenerate(lang) }, modifier = Modifier.padding(top = 12.dp)) {
                    Text(stringResource(R.string.generate_notes_in, notesLangLabel(lang)))
                }
            }
        }
        is Content.Failed -> ErrorBanner(study.error, onRetry = null)
        is Content.Ready -> StudyContentView(study.value)
    }
}

@Composable
private fun StudyContentView(study: StudyDto) {
    val content = study.content
    var mode by rememberSaveable(study.lang) { mutableStateOf(StudyMode.FLASHCARDS) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == StudyMode.FLASHCARDS,
                onClick = { mode = StudyMode.FLASHCARDS },
                label = { Text(stringResource(R.string.flashcards_count, content.flashcards.size)) },
            )
            FilterChip(
                selected = mode == StudyMode.QUIZ,
                onClick = { mode = StudyMode.QUIZ },
                label = { Text(stringResource(R.string.quiz_count, content.mcqs.size)) },
            )
            FilterChip(
                selected = mode == StudyMode.QUESTIONS,
                onClick = { mode = StudyMode.QUESTIONS },
                label = { Text(stringResource(R.string.questions_count, content.questions.size)) },
            )
        }
        Box(Modifier.fillMaxSize()) {
            when (mode) {
                StudyMode.FLASHCARDS -> Flashcards(content.flashcards, study.lang)
                StudyMode.QUIZ -> Quiz(content.mcqs, study.lang)
                StudyMode.QUESTIONS -> Questions(content.questions)
            }
        }
    }
}

@Composable
private fun Flashcards(cards: List<FlashcardDto>, key: String) {
    if (cards.isEmpty()) {
        CenteredMessage(stringResource(R.string.nothing_here))
        return
    }
    var index by rememberSaveable(key) { mutableIntStateOf(0) }
    var flipped by rememberSaveable(key) { mutableStateOf(false) }
    val card = cards[index.coerceIn(0, cards.lastIndex)]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${index + 1} / ${cards.size}", style = MaterialTheme.typography.labelLarge)
        Card(
            onClick = { flipped = !flipped },
            colors = CardDefaults.cardColors(
                containerColor = if (flipped) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(20.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (flipped) card.back else card.front,
                    style = if (flipped) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(stringResource(R.string.tap_to_flip), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                index = (index - 1).coerceAtLeast(0)
                flipped = false
            }, enabled = index > 0) { Text(stringResource(R.string.previous)) }
            Button(onClick = {
                index = (index + 1).coerceAtMost(cards.lastIndex)
                flipped = false
            }, enabled = index < cards.lastIndex) { Text(stringResource(R.string.next)) }
        }
    }
}

@Composable
private fun Quiz(mcqs: List<McqDto>, key: String) {
    if (mcqs.isEmpty()) {
        CenteredMessage(stringResource(R.string.nothing_here))
        return
    }
    var index by rememberSaveable(key) { mutableIntStateOf(0) }
    var chosen by rememberSaveable(key) { mutableIntStateOf(-1) }
    var score by rememberSaveable(key) { mutableIntStateOf(0) }
    var finished by rememberSaveable(key) { mutableStateOf(false) }

    if (finished) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.quiz_score, score, mcqs.size), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = {
                index = 0
                chosen = -1
                score = 0
                finished = false
            }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.try_again)) }
        }
        return
    }

    val mcq = mcqs[index.coerceIn(0, mcqs.lastIndex)]
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.question_n_of, index + 1, mcqs.size), style = MaterialTheme.typography.labelLarge)
        Text(mcq.question, style = MaterialTheme.typography.titleMedium)
        mcq.options.forEachIndexed { i, option ->
            val answered = chosen >= 0
            val border = when {
                answered && i == mcq.answerIndex -> MaterialTheme.colorScheme.tertiary
                answered && i == chosen -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            OutlinedCard(
                border = BorderStroke(if (answered && (i == mcq.answerIndex || i == chosen)) 2.dp else 1.dp, border),
                modifier = Modifier.fillMaxWidth().clickable(enabled = !answered) {
                    chosen = i
                    if (i == mcq.answerIndex) score++
                },
            ) {
                Text("${'A' + i}. $option", modifier = Modifier.padding(14.dp))
            }
        }
        if (chosen >= 0) {
            Text(
                stringResource(if (chosen == mcq.answerIndex) R.string.correct else R.string.not_quite),
                color = if (chosen == mcq.answerIndex) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
            if (mcq.explanation.isNotBlank()) Text(mcq.explanation, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                if (index >= mcqs.lastIndex) {
                    finished = true
                } else {
                    index++
                    chosen = -1
                }
            }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(if (index >= mcqs.lastIndex) R.string.see_score else R.string.next))
            }
        }
    }
}

@Composable
private fun Questions(questions: List<WrittenQuestionDto>) {
    if (questions.isEmpty()) {
        CenteredMessage(stringResource(R.string.nothing_here))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(questions) { i, q ->
            var open by rememberSaveable(q.question) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(if (q.kind == "broad") R.string.broad_question else R.string.short_question),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("${i + 1}. ${q.question}", style = MaterialTheme.typography.titleSmall)
                    if (open) {
                        q.answerPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                    TextButton(onClick = { open = !open }) {
                        Text(stringResource(if (open) R.string.hide_answer else R.string.show_answer))
                    }
                }
            }
        }
    }
}
