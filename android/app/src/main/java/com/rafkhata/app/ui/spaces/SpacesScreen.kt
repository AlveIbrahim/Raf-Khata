@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.spaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.SpaceDto
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.EmptyState
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.text
import com.rafkhata.app.ui.nav.MainNavBar
import com.rafkhata.app.ui.nav.MainTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SpacesViewModel(private val app: AppContainer) : ViewModel() {
    data class State(
        val spaces: List<SpaceDto>? = null,
        val refreshing: Boolean = false,
        val error: ErrorMessage? = null,
        val dialogBusy: Boolean = false,
        val dialogError: ErrorMessage? = null,
        val opened: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.spaces.cached()?.let { cached -> _state.update { it.copy(spaces = cached) } }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            runCatching { app.spaces.list() }
                .onSuccess { list -> _state.update { it.copy(spaces = list, refreshing = false, error = null) } }
                .onFailure { e -> _state.update { it.copy(refreshing = false, error = e.toErrorMessage()) } }
        }
    }

    fun create(name: String, university: String, section: String) = dialogAction { app.spaces.create(name, university, section) }

    fun join(code: String) = dialogAction { app.spaces.join(code) }

    fun clearDialog() = _state.update { it.copy(dialogError = null, dialogBusy = false) }

    fun consumeOpened() = _state.update { it.copy(opened = null) }

    private fun dialogAction(block: suspend () -> SpaceDto) {
        viewModelScope.launch {
            _state.update { it.copy(dialogBusy = true, dialogError = null) }
            runCatching { block() }
                .onSuccess { space ->
                    _state.update { it.copy(dialogBusy = false, opened = space.id) }
                    runCatching { app.courses.refresh() } // section courses become visible
                    refresh()
                }
                .onFailure { e -> _state.update { it.copy(dialogBusy = false, dialogError = e.toErrorMessage()) } }
        }
    }
}

private enum class SpaceDialog { NONE, CREATE, JOIN }

@Composable
fun SpacesScreen(onOpenTab: (MainTab) -> Unit, onOpenSpace: (String) -> Unit) {
    val app = LocalContext.current.container
    val vm: SpacesViewModel = viewModel { SpacesViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(SpaceDialog.NONE) }

    LaunchedEffect(state.opened) {
        state.opened?.let {
            dialog = SpaceDialog.NONE
            vm.consumeOpened()
            onOpenSpace(it)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_sections)) }) },
        bottomBar = { MainNavBar(MainTab.SPACES, onOpenTab) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                state.error?.let { error -> item { ErrorBanner(error, onRetry = vm::refresh) } }
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sections_explainer), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { dialog = SpaceDialog.JOIN }) { Text(stringResource(R.string.join_section)) }
                            OutlinedButton(onClick = { dialog = SpaceDialog.CREATE }) { Text(stringResource(R.string.create_section)) }
                        }
                    }
                }
                val spaces = state.spaces
                if (spaces != null && spaces.isEmpty()) {
                    item { EmptyState(Icons.Filled.Groups, stringResource(R.string.no_sections)) }
                }
                items(spaces.orEmpty(), key = { it.id }) { space ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpenSpace(space.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(space.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                space.sectionLabel?.takeIf { it.isNotBlank() },
                                space.university?.takeIf { it.isNotBlank() },
                                roleLabel(space.role),
                                stringResource(R.string.member_count, space.memberCount),
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

    when (dialog) {
        SpaceDialog.CREATE -> CreateSpaceDialog(
            busy = state.dialogBusy,
            error = state.dialogError,
            onCreate = vm::create,
            onDismiss = {
                dialog = SpaceDialog.NONE
                vm.clearDialog()
            },
        )
        SpaceDialog.JOIN -> JoinSpaceDialog(
            busy = state.dialogBusy,
            error = state.dialogError,
            onJoin = vm::join,
            onDismiss = {
                dialog = SpaceDialog.NONE
                vm.clearDialog()
            },
        )
        SpaceDialog.NONE -> Unit
    }
}

@Composable
fun roleLabel(role: String): String = when (role) {
    "owner" -> stringResource(R.string.role_owner)
    "cr" -> stringResource(R.string.role_cr)
    else -> stringResource(R.string.role_member)
}

@Composable
private fun CreateSpaceDialog(
    busy: Boolean,
    error: ErrorMessage?,
    onCreate: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var university by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_section)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.section_name)) }, singleLine = true)
                OutlinedTextField(university, { university = it }, label = { Text(stringResource(R.string.university)) }, singleLine = true)
                OutlinedTextField(section, { section = it }, label = { Text(stringResource(R.string.section_label)) }, singleLine = true)
                Text(stringResource(R.string.create_section_hint), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, university, section) }, enabled = !busy && name.isNotBlank()) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun JoinSpaceDialog(busy: Boolean, error: ErrorMessage?, onJoin: (String) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.join_section)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.join_section_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    code,
                    { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(16) },
                    label = { Text(stringResource(R.string.invite_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onJoin(code) }, enabled = !busy && code.length >= 4) { Text(stringResource(R.string.join)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
