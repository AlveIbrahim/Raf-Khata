package com.rafkhata.app.notify

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.rafkhata.app.BuildConfig
import com.rafkhata.app.data.api.DeviceIn
import com.rafkhata.app.data.api.RafKhataApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Firebase Cloud Messaging, set up from the `rafkhata.firebase*` values in local.properties.
 * Without them the app works normally, just without push notifications.
 */
class PushManager(private val context: Context, private val api: RafKhataApi) {
    val configured: Boolean =
        BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    private var ready = false

    fun init() {
        if (!configured) return
        ready = runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            true
        }.getOrElse {
            Log.w(TAG, "Firebase init failed", it)
            false
        }
    }

    /** Sends this phone's FCM token to the backend so "notes ready" pushes reach it. */
    suspend fun registerDevice() {
        val token = currentToken() ?: return
        sendToken(token)
    }

    suspend fun sendToken(token: String) {
        runCatching {
            api.registerDevice(
                DeviceIn(fcmToken = token, appVersion = BuildConfig.VERSION_NAME, locale = Locale.getDefault().toLanguageTag()),
            )
        }.onFailure { Log.w(TAG, "device registration failed", it) }
    }

    /** Before sign-out: stop pushes for this account on this phone. */
    suspend fun unregisterDevice() {
        val token = currentToken() ?: return
        runCatching { api.unregisterDevice(token) }
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }

    private suspend fun currentToken(): String? {
        if (!ready) return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    cont.resume(if (task.isSuccessful) task.result else null)
                }
            }.onFailure { cont.resume(null) }
        }
    }

    private companion object {
        const val TAG = "PushManager"
    }
}
