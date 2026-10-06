package com.rafkhata.app.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rafkhata.app.BuildConfig
import com.rafkhata.app.R
import com.rafkhata.app.container
import com.rafkhata.app.data.api.ErrorMessage
import com.rafkhata.app.data.api.UserDto
import com.rafkhata.app.data.api.toErrorMessage
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.ui.common.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SignInViewModel(private val app: AppContainer) : ViewModel() {
    data class State(val busy: Boolean = false, val error: ErrorMessage? = null, val signedIn: UserDto? = null)

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun setBusy(busy: Boolean) = _state.update { it.copy(busy = busy) }

    fun showError(error: ErrorMessage?) = _state.update { it.copy(busy = false, error = error) }

    fun signInWithGoogle(idToken: String) = signIn { app.auth.signInWithGoogle(idToken) }

    fun devLogin(email: String, name: String) = signIn { app.auth.devLogin(email, name) }

    private fun signIn(block: suspend () -> UserDto) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { block() }
                .onSuccess { user ->
                    app.onSignedIn()
                    _state.update { it.copy(busy = false, signedIn = user) }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = e.toErrorMessage()) } }
        }
    }
}

@Composable
fun SignInScreen(onSignedIn: (onboarded: Boolean) -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val vm: SignInViewModel = viewModel { SignInViewModel(app) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val googleConfigured = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    state.signedIn?.let { user ->
        LaunchedEffect(user.id) { onSignedIn(user.onboarded) }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.signin_explainer),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            if (googleConfigured) {
                Button(
                    onClick = {
                        scope.launch {
                            vm.setBusy(true)
                            when (val result = GoogleSignIn.requestIdToken(context, BuildConfig.GOOGLE_WEB_CLIENT_ID)) {
                                is GoogleSignIn.Result.Token -> vm.signInWithGoogle(result.idToken)
                                GoogleSignIn.Result.Cancelled -> vm.showError(null)
                                is GoogleSignIn.Result.Failed ->
                                    vm.showError(ErrorMessage(R.string.error_google_signin, result.message))
                            }
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.signin_google)) }
            } else {
                Text(
                    stringResource(R.string.signin_google_not_configured),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.busy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
            }
            state.error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it.text(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }

            if (BuildConfig.DEV_LOGIN) {
                DevLogin(enabled = !state.busy, onLogin = vm::devLogin)
            }
        }
    }
}

@Composable
private fun DevLogin(enabled: Boolean, onLogin: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("student@example.com") }
    var name by rememberSaveable { mutableStateOf("Test Student") }
    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.dev_login_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.dev_login_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { onLogin(email, name) }, enabled = enabled && email.contains("@"), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dev_login_button))
    }
}
