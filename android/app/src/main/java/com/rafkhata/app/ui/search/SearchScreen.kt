@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.SearchHitDto
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.BackButton
import com.rafkhata.app.ui.common.EmptyState
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.Fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(private val app: AppContainer) : ViewModel() {
    data class State(
        val query: String = "",
        val hits: List<SearchHitDto>? = null,
        val searching: Boolean = false,
        val error: ErrorMessage? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        job?.cancel()
        if (query.trim().length < 2) {
            _state.update { it.copy(hits = null, searching = false, error = null) }
            return
        }
        job = viewModelScope.launch {
            delay(400)
            search(query.trim())
        }
    }

    fun searchNow() {
        job?.cancel()
        val query = _state.value.query.trim()
        if (query.length >= 2) job = viewModelScope.launch { search(query) }
    }

    private suspend fun search(query: String) {
        _state.update { it.copy(searching = true, error = null) }
        runCatching { app.lectures.search(query) }
            .onSuccess { hits -> _state.update { it.copy(hits = hits, searching = false) } }
            .onFailure { e -> _state.update { it.copy(searching = false, error = e.toErrorMessage()) } }
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenHit: (lectureId: String, seekMs: Long, tab: Int) -> Unit) {
    val app = LocalContext.current.container
    val vm: SearchViewModel = viewModel { SearchViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.searchNow() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorBanner(it, onRetry = vm::searchNow) }
            val hits = state.hits
            when {
                hits == null -> EmptyState(Icons.Filled.Search, stringResource(R.string.search_explainer))
                hits.isEmpty() -> EmptyState(Icons.Filled.Search, stringResource(R.string.no_results))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(hits) { hit ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val seekMs = hit.tStart?.let { (it * 1000).toLong() } ?: -1L
                                    onOpenHit(hit.lectureId, seekMs, if (hit.source == "transcript") 1 else 0)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                listOfNotNull(hit.courseTitle, hit.lectureTitle.ifBlank { null }).joinToString(" · "),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                            Text(
                                listOfNotNull(
                                    stringResource(if (hit.source == "transcript") R.string.tab_transcript else R.string.tab_notes),
                                    hit.tStart?.let { Fmt.duration(it) },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}
