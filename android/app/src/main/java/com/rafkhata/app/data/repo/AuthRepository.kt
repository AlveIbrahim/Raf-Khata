package com.rafkhata.app.data.repo

import com.rafkhata.app.data.api.DevLoginIn
import com.rafkhata.app.data.api.GoogleLoginIn
import com.rafkhata.app.data.api.RafKhataApi
import com.rafkhata.app.data.api.RefreshIn
import com.rafkhata.app.data.api.UserDto
import com.rafkhata.app.data.api.UserPatch
import com.rafkhata.app.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.OutputStream

class AuthRepository(private val api: RafKhataApi, private val tokens: TokenStore) {
    val user: StateFlow<UserDto?> = tokens.user
    val isSignedIn: Boolean get() = tokens.isSignedIn

    fun currentUser(): UserDto? = tokens.user.value

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the refresh token is rejected and the user has to sign in again. */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    fun notifySessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }

    suspend fun signInWithGoogle(idToken: String): UserDto =
        api.googleLogin(GoogleLoginIn(idToken)).also(tokens::save).user

    suspend fun devLogin(email: String, name: String): UserDto =
        api.devLogin(DevLoginIn(email.trim(), name.trim())).also(tokens::save).user

    suspend fun refreshProfile(): UserDto = api.me().also(tokens::updateUser)

    suspend fun updateProfile(patch: UserPatch): UserDto = api.updateMe(patch).also(tokens::updateUser)

    /** Revokes the refresh token on the server (best effort) and forgets the session locally. */
    suspend fun endSession() {
        tokens.refreshToken?.let { refresh -> runCatching { api.logout(RefreshIn(refresh)) } }
        tokens.clear()
    }

    suspend fun deleteAccount() {
        val response = api.deleteMe()
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        tokens.clear()
    }

    /** Streams the `GET /me/export` JSON into [out]. */
    suspend fun exportData(out: OutputStream) = withContext(Dispatchers.IO) {
        api.exportMe().byteStream().use { it.copyTo(out) }
    }
}
