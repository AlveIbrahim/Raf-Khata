package com.rafkhata.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.rafkhata.app.notify.Notifications
import com.rafkhata.app.ui.nav.DeepLink
import com.rafkhata.app.ui.nav.RafKhataNavHost
import com.rafkhata.app.ui.theme.RafKhataTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** The only activity. AppCompat is used for the per-app language setting. */
class MainActivity : AppCompatActivity() {
    private val deepLinks = MutableStateFlow<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            RafKhataTheme {
                RafKhataNavHost(container = container, deepLinks = deepLinks)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val extras = intent?.extras ?: return
        // Notification taps: ours, or FCM's own when the app was in the background (data keys as extras).
        val link = when {
            extras.getString(Notifications.EXTRA_LECTURE_ID) != null ->
                DeepLink.Lecture(extras.getString(Notifications.EXTRA_LECTURE_ID).orEmpty())
            extras.getString(Notifications.EXTRA_OPEN) == Notifications.OPEN_RECORD -> DeepLink.Record
            extras.getString(Notifications.EXTRA_OPEN) == Notifications.OPEN_DEADLINES -> DeepLink.Deadlines
            else -> null
        }
        if (link != null) deepLinks.value = link
    }
}
