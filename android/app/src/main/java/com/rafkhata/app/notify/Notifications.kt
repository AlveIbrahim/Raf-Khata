package com.rafkhata.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rafkhata.app.MainActivity
import com.rafkhata.app.R

object Notifications {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_UPLOADS = "uploads"

    /** Must match the channel id the backend puts in its FCM messages. */
    const val CHANNEL_NOTES = "notes_ready"
    const val CHANNEL_REMINDERS = "reminders"

    const val ID_RECORDING = 1
    private const val ID_UPLOAD_FAILED = 2

    const val EXTRA_LECTURE_ID = "lecture_id"
    const val EXTRA_OPEN = "open"
    const val OPEN_RECORD = "record"
    const val OPEN_DEADLINES = "deadlines"

    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        listOf(
            NotificationChannelCompat.Builder(CHANNEL_RECORDING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_recording)),
            NotificationChannelCompat.Builder(CHANNEL_UPLOADS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_uploads)),
            NotificationChannelCompat.Builder(CHANNEL_NOTES, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_notes)),
            NotificationChannelCompat.Builder(CHANNEL_REMINDERS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.channel_reminders)),
        ).forEach { manager.createNotificationChannel(it.build()) }
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by canPost
    fun post(context: Context, id: Int, notification: Notification) {
        if (canPost(context)) NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun openAppIntent(context: Context, requestCode: Int, configure: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply(configure)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun notesReady(context: Context, lectureId: String, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_NOTES)
            .setSmallIcon(R.drawable.ic_stat_rk)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context, lectureId.hashCode()) { putExtra(EXTRA_LECTURE_ID, lectureId) })
            .build()
        post(context, lectureId.hashCode(), notification)
    }

    fun uploadFailed(context: Context, title: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_UPLOADS)
            .setSmallIcon(R.drawable.ic_stat_rk)
            .setContentTitle(context.getString(R.string.upload_failed_title))
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, ID_UPLOAD_FAILED))
            .build()
        post(context, ID_UPLOAD_FAILED, notification)
    }

    fun deadlineReminder(context: Context, deadlineId: String, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_rk)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(context, deadlineId.hashCode()) { putExtra(EXTRA_OPEN, OPEN_DEADLINES) })
            .build()
        post(context, deadlineId.hashCode(), notification)
    }
}
