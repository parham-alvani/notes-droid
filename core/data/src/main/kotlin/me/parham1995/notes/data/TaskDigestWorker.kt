package me.parham1995.notes.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.parham1995.notes.data.database.TaskRow
import java.time.LocalDate

/**
 * Once a day, what is open -- as two notifications, one per question asked
 * of the index, each on its own channel.
 *
 * Overdue tasks are counted, not listed. The vault this was built for has
 * 120 of them: a notification per task is 120 notifications, and one listing
 * them is a wall of text nobody reads on a lock screen. The tasks due today
 * are named, because there are a handful and they are the ones the evening
 * is for -- "3 due today" sends you to the list to find out which, and the
 * notification can simply say.
 *
 * Two channels rather than one because the two are not the same kind of
 * news. The overdue count is the same number most nights and a person may
 * well want it silent; what is due today is the thing to be told. Android
 * lets each channel be muted, given a sound or shown on the lock screen on
 * its own, and nothing in the app has to grow a setting for it.
 *
 * It reads whatever the last sync left behind rather than syncing first. A
 * digest that waits on the network is a digest that silently does not arrive.
 */
@HiltWorker
class TaskDigestWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted params: WorkerParameters,
        private val repository: VaultRepository,
        private val settings: SettingsStore,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val current = settings.current()
            // The schedule outlives the setting being switched off between
            // runs, so the check is here rather than only at arming time.
            if (!current.taskDigest) return Result.success()

            val today = LocalDate.now().toString()
            val overdue = repository.overdueCount(today)
            val due = repository.dueToday(today)

            runCatching { notify(overdue, due) }
            return Result.success()
        }

        /**
         * Posts each notification that has something to say and takes down
         * the one that does not: yesterday's "2 overdue" left in the shade
         * after the two were ticked would be a lie.
         */
        private fun notify(
            overdue: Int,
            due: List<TaskRow>,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    DUE_CHANNEL_ID,
                    context.getString(R.string.digest_channel_due),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    OVERDUE_CHANNEL_ID,
                    context.getString(R.string.digest_channel_overdue),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            // The single channel both used to share. Left in place it would
            // sit in the system's list of this app's channels, wired to nothing.
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

            val open = openTasks()
            if (due.isEmpty()) {
                manager.cancel(DUE_NOTIFICATION_ID)
            } else {
                manager.notify(DUE_NOTIFICATION_ID, dueTodayNotification(context, due, open))
            }
            if (overdue == 0) {
                manager.cancel(OVERDUE_NOTIFICATION_ID)
            } else {
                manager.notify(OVERDUE_NOTIFICATION_ID, overdueNotification(context, overdue, open))
            }
        }

        /**
         * Opens the app on the task list.
         *
         * Built from the launcher intent rather than by naming the activity,
         * because this module deliberately knows nothing about the UI.
         */
        private fun openTasks(): PendingIntent? {
            val launch =
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: return null
            launch.putExtra(EXTRA_OPEN_TASKS, true)
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        companion object {
            const val UNIQUE_WORK = "task-digest"

            /** What is due today, by name. */
            const val DUE_CHANNEL_ID = "task-due-today"

            /** How many are overdue. */
            const val OVERDUE_CHANNEL_ID = "task-overdue"

            /** The one channel both were posted on before they were two. */
            private const val LEGACY_CHANNEL_ID = "task-digest"

            const val DUE_NOTIFICATION_ID = 2
            const val OVERDUE_NOTIFICATION_ID = 3

            /** Set on the launch intent so the app opens on the task list. */
            const val EXTRA_OPEN_TASKS = "open_tasks"

            /**
             * How many of today's tasks the notification names. Android shows
             * about this many lines of an expanded notification before it
             * cuts the rest off itself, silently; past it the digest says how
             * many it left out.
             */
            const val MAX_LINES = 5
        }
    }

/**
 * The tasks due today: the count as the headline and, when it is expanded,
 * the tasks by name with the note each is in.
 */
internal fun dueTodayNotification(
    context: Context,
    due: List<TaskRow>,
    open: PendingIntent?,
): Notification {
    val resources = context.resources
    val headline = resources.getQuantityString(R.plurals.digest_due_today, due.size, due.size)
    val style = NotificationCompat.InboxStyle().setBigContentTitle(headline)
    due.take(TaskDigestWorker.MAX_LINES).forEach { task ->
        style.addLine(context.getString(R.string.digest_line, task.text, task.noteTitle))
    }
    val more = due.size - TaskDigestWorker.MAX_LINES
    if (more > 0) style.setSummaryText(resources.getQuantityString(R.plurals.digest_more, more, more))
    return digest(context, TaskDigestWorker.DUE_CHANNEL_ID, headline, open)
        .setStyle(style)
        .build()
}

/** How many tasks are overdue, and nothing more: the list is one tap away. */
internal fun overdueNotification(
    context: Context,
    overdue: Int,
    open: PendingIntent?,
): Notification {
    val headline = context.resources.getQuantityString(R.plurals.digest_overdue, overdue, overdue)
    return digest(context, TaskDigestWorker.OVERDUE_CHANNEL_ID, headline, open).build()
}

private fun digest(
    context: Context,
    channel: String,
    headline: String,
    open: PendingIntent?,
): NotificationCompat.Builder =
    NotificationCompat
        .Builder(context, channel)
        .setContentTitle(context.getString(R.string.digest_title))
        .setContentText(headline)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setAutoCancel(true)
        .setContentIntent(open)
