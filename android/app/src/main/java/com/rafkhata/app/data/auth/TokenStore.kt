package com.rafkhata.app.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.rafkhata.app.data.api.TokenOut
import com.rafkhata.app.data.api.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session tokens, encrypted with an AES key that never leaves the Android Keystore.
 * The signed-in user's profile is kept alongside so the app can start offline.
 */
class TokenStore(context: Context, private val json: Json) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val crypto = KeystoreCrypto("rafkhata_session")

    @Volatile
    var accessToken: String? = prefs.getString(KEY_ACCESS, null)?.let(crypto::decrypt)
        private set

    @Volatile
    var refreshToken: String? = prefs.getString(KEY_REFRESH, null)?.let(crypto::decrypt)
        private set

    private val _user = MutableStateFlow(
        prefs.getString(KEY_USER, null)?.let { runCatching { json.decodeFromString(UserDto.serializer(), it) }.getOrNull() }
            ?.takeIf { refreshToken != null },
    )
    val user: StateFlow<UserDto?> = _user.asStateFlow()

    val isSignedIn: Boolean get() = refreshToken != null && _user.value != null

    @Synchronized
    fun save(tokens: TokenOut) {
        accessToken = tokens.accessToken
        refreshToken = tokens.refreshToken
        prefs.edit(commit = true) {
            putString(KEY_ACCESS, crypto.encrypt(tokens.accessToken))
            putString(KEY_REFRESH, crypto.encrypt(tokens.refreshToken))
            putString(KEY_USER, json.encodeToString(UserDto.serializer(), tokens.user))
        }
        _user.value = tokens.user
    }

    fun updateUser(user: UserDto) {
        prefs.edit { putString(KEY_USER, json.encodeToString(UserDto.serializer(), user)) }
        _user.value = user
    }

    @Synchronized
    fun clear() {
        accessToken = null
        refreshToken = null
        prefs.edit(commit = true) { clear() }
        _user.value = null
    }

    private companion object {
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_USER = "user"
    }
}

private class KeystoreCrypto(private val alias: String) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Null if the value can't be decrypted, e.g. after the key was wiped with the app data. */
    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
