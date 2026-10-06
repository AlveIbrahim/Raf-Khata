@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.spaces

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.SpaceDto
import com.rafkhata.app.data.api.SpaceMemberDto
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.BackButton
import com.rafkhata.app.ui.common.ConfirmDialog
import com.rafkhata.app.ui.common.ErrorBanner
import com.rafkhata.app.ui.common.LoadingBox
import com.rafkhata.app.ui.common.SectionTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SpaceViewModel(private val app: AppContainer, private val spaceId: String) : ViewModel() {
    data class State(
        val space: SpaceDto? = null,
        val error: ErrorMessage? = null,
        val busy: Boolean = false,
        val left: Boolean = false,
    )

    val myId: String? = app.auth.user.value?.id
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            app.spaces.cachedSpace(spaceId)?.let { cached -> _state.update { it.copy(space = cached) } }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { app.spaces.get(spaceId) }
                .onSuccess { space -> _state.update { it.copy(space = space, error = null) } }
                .onFailure { e -> _state.update { it.copy(error = e.toErrorMessage()) } }
        }
    }

    fun rotateCode() = act {
        val updated = app.spaces.rotateInviteCode(spaceId)
        _state.update { s -> s.copy(space = s.space?.copy(inviteCode = updated.inviteCode)) }
    }

    fun setRole(userId: String, role: String) = act {
        val updated = app.spaces.setRole(spaceId, userId, role)
        _state.update { it.copy(space = updated) }
    }

    fun leave() = act {
        app.spaces.leave(spaceId)
        runCatching { app.courses.refresh() }
        _state.update { it.copy(left = true) }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { block() }.onFailure { e -> _state.update { it.copy(error = e.toErrorMessage()) } }
            _state.update { it.copy(busy = false) }
        }
    }
}

@Composable
fun SpaceScreen(spaceId: String, onBack: () -> Unit, onLeft: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val vm: SpaceViewModel = viewModel { SpaceViewModel(app, spaceId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }
    val space = state.space

    LaunchedEffect(state.left) { if (state.left) onLeft() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(space?.name.orEmpty()) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        if (space == null) {
            if (state.error != null) {
                Column(Modifier.padding(padding)) { ErrorBanner(state.error!!, onRetry = vm::refresh) }
            } else {
                LoadingBox(Modifier.padding(padding))
            }
            return@Scaffold
        }
        val canManage = space.role == "owner" || space.role == "cr"
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            state.error?.let { error -> item { ErrorBanner(error, onRetry = vm::refresh) } }
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.invite_code), style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                space.inviteCode,
                                style = MaterialTheme.typography.headlineMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { copyToClipboard(context, space.inviteCode) }) {
                                Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy))
                            }
                            IconButton(onClick = {
                                val text = context.getString(R.string.invite_message, space.name, space.inviteCode)
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                                context.startActivity(Intent.createChooser(send, null))
                            }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
                            if (canManage) {
                                IconButton(onClick = vm::rotateCode, enabled = !state.busy) {
                                    Icon(Icons.Filled.Refresh, stringResource(R.string.new_code))
                                }
                            }
                        }
                        Text(
                            stringResource(R.string.invite_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.members_count, space.members.size)) }
            items(space.members, key = { it.userId }) { member ->
                MemberRow(
                    member = member,
                    isMe = member.userId == vm.myId,
                    canChange = space.role == "owner" && member.role != "owner",
                    onSetRole = { role -> vm.setRole(member.userId, role) },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item {
                OutlinedButton(
                    onClick = { confirmLeave = true },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) { Text(stringResource(R.string.leave_section)) }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.leave_section),
            text = stringResource(R.string.leave_section_text),
            confirmLabel = stringResource(R.string.leave),
            destructive = true,
            onConfirm = {
                confirmLeave = false
                vm.leave()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun MemberRow(member: SpaceMemberDto, isMe: Boolean, canChange: Boolean, onSetRole: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (isMe) stringResource(R.string.member_me, member.name) else member.name, style = MaterialTheme.typography.bodyLarge)
            Text(roleLabel(member.role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canChange) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (member.role == "cr") {
                        DropdownMenuItem(text = { Text(stringResource(R.string.make_member)) }, onClick = {
                            menu = false
                            onSetRole("member")
                        })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.make_cr)) }, onClick = {
                            menu = false
                            onSetRole("cr")
                        })
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
}
