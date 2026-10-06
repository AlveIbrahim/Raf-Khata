@file:OptIn(ExperimentalMaterial3Api::class)

package com.rafkhata.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.UserPatch
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.BackButton
import com.rafkhata.app.ui.common.NotesLangPicker
import com.rafkhata.app.ui.common.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProfileViewModel(private val app: AppContainer) : ViewModel() {
    data class State(
        val name: String = "",
        val university: String = "",
        val department: String = "",
        val batch: String = "",
        val section: String = "",
        val notesLang: String = "bn",
        val saving: Boolean = false,
        val error: ErrorMessage? = null,
        val saved: Boolean = false,
    )

    private val _state = MutableStateFlow(
        app.auth.user.value.let { u ->
            State(
                name = u?.name.orEmpty(),
                university = u?.university.orEmpty(),
                department = u?.department.orEmpty(),
                batch = u?.batch.orEmpty(),
                section = u?.section.orEmpty(),
                notesLang = u?.notesLang ?: "bn",
            )
        },
    )
    val state = _state.asStateFlow()

    fun edit(transform: (State) -> State) = _state.update { transform(it).copy(error = null) }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            runCatching {
                app.auth.updateProfile(
                    UserPatch(
                        name = s.name.trim(),
                        university = s.university.trim(),
                        department = s.department.trim(),
                        batch = s.batch.trim(),
                        section = s.section.trim(),
                        notesLang = s.notesLang,
                        onboarded = true,
                    ),
                )
            }.onSuccess { _state.update { it.copy(saving = false, saved = true) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.toErrorMessage()) } }
        }
    }
}

@Composable
fun ProfileScreen(onboarding: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.container
    val vm: ProfileViewModel = viewModel { ProfileViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (onboarding) R.string.profile_welcome else R.string.profile_title)) },
                navigationIcon = { if (!onboarding) BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onboarding) {
                Text(stringResource(R.string.profile_onboarding_hint), style = MaterialTheme.typography.bodyMedium)
            }
            Field(state.name, R.string.name) { v -> vm.edit { it.copy(name = v) } }
            Field(state.university, R.string.university) { v -> vm.edit { it.copy(university = v) } }
            Field(state.department, R.string.department) { v -> vm.edit { it.copy(department = v) } }
            Field(state.batch, R.string.batch) { v -> vm.edit { it.copy(batch = v) } }
            Field(state.section, R.string.section) { v -> vm.edit { it.copy(section = v) } }
            Text(
                stringResource(R.string.notes_language),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            NotesLangPicker(selected = state.notesLang, onSelect = { lang -> vm.edit { it.copy(notesLang = lang ?: "bn") } })
            state.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = vm::save,
                enabled = !state.saving && state.name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(if (onboarding) R.string.continue_ else R.string.save)) }
        }
    }
}

@Composable
private fun Field(value: String, label: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
