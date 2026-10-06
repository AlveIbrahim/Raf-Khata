package com.rafkhata.app.notify

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rafkhata.app.R
import com.rafkhata.app.container
import kotlinx.coroutines.launch

/** Receives "notes ready" pushes. In the background FCM shows them itself; this handles the rest. */
class FcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = applicationContext.container
        if (app.auth.isSignedIn) app.appScope.launch { app.push.sendToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "notes_ready") return
        val lectureId = data["lecture_id"] ?: return
        val title = message.notification?.title ?: getString(R.string.notes_ready)
        val body = message.notification?.body.orEmpty()
        Notifications.notesReady(this, lectureId, title, body)
        applicationContext.container.lectureEvents.tryEmit(lectureId)
    }
}
