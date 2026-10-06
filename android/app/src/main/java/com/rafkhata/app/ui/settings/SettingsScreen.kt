@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.settings

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.BuildConfig
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.data.db.RecordingEntity
import com.rafkhata.app.data.settings.AppSettings
import com.rafkhata.app.data.settings.MicSource
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.notify.Notifications
import com.rafkhata.app.ui.common.BackButton
import com.rafkhata.app.ui.common.ConfirmDialog
import com.rafkhata.app.ui.common.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val app: AppContainer) : ViewModel() {
    data class State(
        val busy: Boolean = false,
        val error: ErrorMessage? = null,
        val signedOut: Boolean = false,
        val exported: Boolean = false,
    )

    val user = app.auth.user
    val settings: StateFlow<AppSettings> =
        app.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val recordings: StateFlow<List<RecordingEntity>> =
        app.recordings.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            app.settings.setWifiOnly(value)
            app.uploads.requeueAll(replace = true)
        }
    }

    fun setReminders(value: Boolean) {
        viewModelScope.launch {
            app.settings.setDeadlineReminders(value)
            app.syncReminders(app.lectures.cachedDeadlines().orEmpty())
        }
    }

    fun setMic(value: MicSource) {
        viewModelScope.launch { app.settings.setMicSource(value) }
    }

    fun export(resolver: ContentResolver, uri: Uri) = launchAction {
        val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("cannot write to $uri")
        stream.use { app.auth.exportData(it) }
        _state.update { it.copy(exported = true) }
    }

    fun signOut() = launchAction {
        app.signOut()
        _state.update { it.copy(signedOut = true) }
    }

    fun deleteAccount() = launchAction {
        app.auth.deleteAccount()
        app.clearLocalData()
        _state.update { it.copy(signedOut = true) }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, exported = false) }
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { e -> _state.update { it.copy(error = e.toErrorMessage()) } }
            _state.update { it.copy(busy = false) }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onEditProfile: () -> Unit, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val vm: SettingsViewModel = viewModel { SettingsViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    var notificationsAllowed by remember { mutableStateOf(Notifications.canPost(context)) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(context.contentResolver, uri)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = Notifications.canPost(context)
    }

    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            state.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (state.exported) Text(stringResource(R.string.export_done), modifier = Modifier.padding(16.dp))

            Header(stringResource(R.string.account))
            Item(
                title = user?.name ?: "",
                subtitle = listOfNotNull(user?.email, user?.university?.takeIf { it.isNotBlank() }).joinToString(" · "),
                onClick = onEditProfile,
            )

            Header(stringResource(R.string.app_language))
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            Column(Modifier.selectableGroup()) {
                listOf("" to R.string.language_system, "bn" to R.string.language_bangla, "en" to R.string.language_english).forEach { (tag, label) ->
                    RadioRow(stringResource(label), selected = current == tag || (tag.isNotEmpty() && current.startsWith(tag))) {
                        AppCompatDelegate.setApplicationLocales(
                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
                        )
                    }
                }
            }

            Header(stringResource(R.string.recording_and_upload))
            SwitchRow(stringResource(R.string.wifi_only), stringResource(R.string.wifi_only_hint), settings.wifiOnlyUploads, vm::setWifiOnly)
            Text(
                stringResource(R.string.mic_source),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            Column(Modifier.selectableGroup()) {
                MicSource.entries.forEach { source ->
                    RadioRow(stringResource(micLabel(source)), selected = settings.micSource == source) { vm.setMic(source) }
                }
            }
            Item(stringResource(R.string.battery_setting), stringResource(R.string.battery_setting_hint)) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            Header(stringResource(R.string.notifications))
            SwitchRow(
                stringResource(R.string.deadline_reminders),
                stringResource(R.string.deadline_reminders_hint),
                settings.deadlineReminders,
                vm::setReminders,
            )
            if (!notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Item(stringResource(R.string.allow_notifications), stringResource(R.string.allow_notifications_hint)) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (!app.push.configured) {
                Text(
                    stringResource(R.string.push_not_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Header(stringResource(R.string.privacy_and_data))
            Text(
                stringResource(R.string.privacy_summary),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Item(stringResource(R.string.export_data), stringResource(R.string.export_data_hint), enabled = !state.busy) {
                exportLauncher.launch("rafkhata-export.json")
            }
            Item(stringResource(R.string.sign_out), null, enabled = !state.busy) { confirm = "signout" }
            Item(stringResource(R.string.delete_account), stringResource(R.string.delete_account_hint), enabled = !state.busy) {
                confirm = "delete"
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    val unsent = recordings.size
    when (confirm) {
        "signout" -> ConfirmDialog(
            title = stringResource(R.string.sign_out),
            text = if (unsent > 0) stringResource(R.string.sign_out_unsent, unsent) else stringResource(R.string.sign_out_text),
            confirmLabel = stringResource(R.string.sign_out),
            destructive = unsent > 0,
            onConfirm = {
                confirm = null
                vm.signOut()
            },
            onDismiss = { confirm = null },
        )
        "delete" -> ConfirmDialog(
            title = stringResource(R.string.delete_account),
            text = stringResource(R.string.delete_account_text),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = {
                confirm = null
                vm.deleteAccount()
            },
            onDismiss = { confirm = null },
        )
    }
}

private fun micLabel(source: MicSource): Int = when (source) {
    MicSource.VOICE_RECOGNITION -> R.string.mic_voice_recognition
    MicSource.MIC -> R.string.mic_default
    MicSource.CAMCORDER -> R.string.mic_camcorder
    MicSource.UNPROCESSED -> R.string.mic_unprocessed
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(title: String, subtitle: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}
