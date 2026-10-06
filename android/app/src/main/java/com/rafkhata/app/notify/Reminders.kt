package com.rafkhata.app.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rafkhata.app.R
import com.rafkhata.app.data.api.DeadlineDto
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Shows one deadline reminder. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        val course = inputData.getString(KEY_COURSE)
        val today = inputData.getBoolean(KEY_TODAY, false)
        val heading = applicationContext.getString(if (today) R.string.reminder_due_today else R.string.reminder_due_tomorrow)
        val text = listOfNotNull(course, title).joinToString(" · ")
        Notifications.deadlineReminder(applicationContext, id, heading, text)
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_COURSE = "course"
        const val KEY_TODAY = "today"
    }
}

/**
 * Deadline reminders: 8 pm the evening before, or 7:30 am on the day if that has already passed.
 * Rescheduled from scratch whenever the deadline list is loaded.
 */
object ReminderScheduler {
    private const val TAG = "deadline-reminder"
    private val EVENING = LocalTime.of(20, 0)
    private val MORNING = LocalTime.of(7, 30)

    fun sync(context: Context, deadlines: List<DeadlineDto>, enabled: Boolean, muted: Set<String>) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(TAG)
        if (!enabled) return
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        for (deadline in deadlines) {
            if (deadline.id in muted) continue
            val due = deadline.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
            val (at, today) = reminderTime(due, now) ?: continue
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(Duration.between(now, at).toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        ReminderWorker.KEY_ID to deadline.id,
                        ReminderWorker.KEY_TITLE to deadline.title,
                        ReminderWorker.KEY_COURSE to deadline.courseTitle,
                        ReminderWorker.KEY_TODAY to today,
                    ),
                )
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork("deadline-${deadline.id}", ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    /** When to remind about something due on [due], and whether that reminder is on the day itself. */
    fun reminderTime(due: LocalDate, now: ZonedDateTime): Pair<ZonedDateTime, Boolean>? {
        val evening = due.minusDays(1).atTime(EVENING).atZone(now.zone)
        if (evening.isAfter(now)) return evening to false
        val morning = due.atTime(MORNING).atZone(now.zone)
        if (morning.isAfter(now)) return morning to true
        return null
    }
}
