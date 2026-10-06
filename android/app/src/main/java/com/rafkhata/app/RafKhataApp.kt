package com.rafkhata.app

import android.app.Application
import android.content.Context
import com.rafkhata.app.di.AppContainer
import com.rafkhata.app.notify.Notifications
import kotlinx.coroutines.launch

class RafKhataApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.push.init()
        // A new process means no recording is running: anything still marked as recording was cut off.
        container.appScope.launch { container.recordings.recoverInterrupted() }
    }
}

val Context.container: AppContainer get() = (applicationContext as RafKhataApp).container
