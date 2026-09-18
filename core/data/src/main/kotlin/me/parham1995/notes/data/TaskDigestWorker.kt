package me.parham1995.notes.data

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
import java.time.LocalDate

/**
 * One notification a day, summarising what is open.
 *
 * A count rather than a list, deliberately. The vault this was built for has
 * 120 overdue tasks: a notification per task is 120 notifications, and a
 * notification listing them is a wall of text nobody reads on a lock screen.
 * The useful message is "you have 120 overdue and 3 due today" -- the list is
 * one tap away, in a screen built to show it.
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
            val due = repository.dueTodayCount(today)
            if (overdue == 0 && due == 0) return Result.success()

            runCatching { notify(overdue, due) }
            return Result.success()
        }

        private fun notify(
            overdue: Int,
            due: Int,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Task digest", NotificationManager.IMPORTANCE_DEFAULT),
            )

            val headline =
                when {
                    overdue == 0 -> "$due due today"
                    due == 0 -> "$overdue overdue"
                    else -> "$overdue overdue, $due due today"
                }

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setContentTitle("Tasks")
                    .setContentText(headline)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setAutoCancel(true)
                    .setContentIntent(openTasks())
                    .build()

            manager.notify(NOTIFICATION_ID, notification)
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
            const val CHANNEL_ID = "task-digest"
            const val NOTIFICATION_ID = 2

            /** Set on the launch intent so the app opens on the task list. */
            const val EXTRA_OPEN_TASKS = "open_tasks"
        }
    }
