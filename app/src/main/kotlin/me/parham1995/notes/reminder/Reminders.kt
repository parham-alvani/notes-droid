package me.parham1995.notes.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.parham1995.notes.MainActivity
import me.parham1995.notes.R
import me.parham1995.notes.data.TaskReminder
import me.parham1995.notes.data.TaskReminders
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.data.runCatchingUnlessCancelled
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task reminders: set, rung, ticked, and set again after a reboot.
 *
 * An alarm per reminder. Exact where the phone allows it -- a reminder at
 * nine that arrives at twenty past is not a reminder -- and inexact rather
 * than not at all where it does not.
 */
@Singleton
class Reminders
    @Inject
    constructor(
        private val store: TaskReminders,
    ) {
        fun set(
            context: Context,
            reminder: TaskReminder,
        ): TaskReminder {
            val kept = store.add(reminder)
            arm(context, kept)
            return kept
        }

        fun cancel(
            context: Context,
            id: Long,
        ) {
            store.remove(id)
            alarms(context).cancel(ringing(context, id))
            context.getSystemService(NotificationManager::class.java)?.cancel(notificationId(id))
        }

        /**
         * Every reminder armed again: a reboot forgets every alarm, and so does
         * the app being force-stopped. One whose time passed while the phone
         * was off rings now rather than never.
         */
        fun rearm(context: Context) = store.all.value.forEach { arm(context, it) }

        private fun arm(
            context: Context,
            reminder: TaskReminder,
        ) {
            val alarms = alarms(context)
            val at = maxOf(reminder.at, System.currentTimeMillis())
            val ring = ringing(context, reminder.id)
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ring)
            }
        }

        private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

        companion object {
            const val ACTION_RING = "me.parham1995.notes.reminder.RING"
            const val ACTION_DONE = "me.parham1995.notes.reminder.DONE"
            const val ACTION_SNOOZE = "me.parham1995.notes.reminder.SNOOZE"

            /** How far Snooze puts a reminder off. */
            const val SNOOZE_MS = 60 * 60_000L
            const val EXTRA_ID = "me.parham1995.notes.reminder.ID"
            const val CHANNEL_ID = "task-reminders"

            fun notificationId(id: Long) = NOTIFICATION_BASE + id.toInt()

            private const val NOTIFICATION_BASE = 1000

            internal fun ringing(
                context: Context,
                id: Long,
            ): PendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    id.toInt(),
                    Intent(context, ReminderReceiver::class.java).setAction(ACTION_RING).putExtra(EXTRA_ID, id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
        }
    }

/**
 * Rings a reminder, ticks its task from the notification, and re-arms every
 * reminder after a reboot or an update.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: Reminders

    @Inject lateinit var store: TaskReminders

    @Inject lateinit var repository: VaultRepository

    @Inject lateinit var notes: NoteDao

    @Inject lateinit var writes: VaultWriteRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> reminders.rearm(context)
            Reminders.ACTION_RING -> later { ring(context, intent.getLongExtra(Reminders.EXTRA_ID, 0L)) }
            Reminders.ACTION_DONE -> later { done(context, intent) }
            Reminders.ACTION_SNOOZE -> snooze(context, intent)
        }
    }

    private fun later(work: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try {
                runCatchingUnlessCancelled { work() }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The task as the index has it now: the line recorded when the reminder
     * was set, if the text there still matches, and otherwise the first open
     * task with that text -- notes grow lines above their tasks.
     */
    private suspend fun task(reminder: TaskReminder): TaskRow? {
        val note = notes.byPath(reminder.vaultId, reminder.path) ?: return null
        val all = repository.tasksIn(note.id)
        return all.firstOrNull { it.line == reminder.line && it.text == reminder.text }
            ?: all.firstOrNull { it.text == reminder.text }
    }

    private suspend fun ring(
        context: Context,
        id: Long,
    ) {
        val reminder = store.byId(id) ?: return
        store.remove(id)
        val task = task(reminder)
        // Ticked since, on the desktop or here: nothing left to remind about.
        if (task != null && task.state != "UNCHECKED" && task.state != "IN_PROGRESS") return
        val vault = repository.vault(reminder.vaultId)
        val canTick = task != null && writes.canWrite(reminder.vaultId).first()
        notify(context, reminder, vault?.label, canTick)
    }

    /** The same reminder again an hour from now, under a new id. */
    private fun snooze(
        context: Context,
        intent: Intent,
    ) {
        val reminder = rung(intent) ?: return
        context.getSystemService(NotificationManager::class.java)?.cancel(Reminders.notificationId(reminder.id))
        reminders.set(context, reminder.copy(id = 0, at = System.currentTimeMillis() + Reminders.SNOOZE_MS))
    }

    private suspend fun done(
        context: Context,
        intent: Intent,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val reminder = rung(intent) ?: return
        val id = reminder.id
        val task = task(reminder)
        val result = task?.let { writes.completeTask(it) }
        if (result == WriteResult.Pushed || result is WriteResult.Queued || result == WriteResult.Unchanged) {
            manager.cancel(Reminders.notificationId(id))
        } else {
            // Said on the notification itself: there is no screen open to say it on.
            notify(context, reminder, repository.vault(reminder.vaultId)?.label, canTick = false, failed = true)
        }
    }

    private fun notify(
        context: Context,
        reminder: TaskReminder,
        vaultName: String?,
        canTick: Boolean,
        failed: Boolean = false,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                Reminders.CHANNEL_ID,
                context.getString(R.string.reminder_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val builder =
            NotificationCompat
                .Builder(context, Reminders.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle(reminder.text)
                .setContentText(
                    if (failed) context.getString(R.string.reminder_tick_failed) else reminder.noteTitle,
                ).setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
        vaultName?.let { builder.setContentIntent(open(context, reminder, it)) }
        // Always offered: putting a reminder off writes nothing to the vault.
        builder.addAction(
            0,
            context.getString(R.string.reminder_snooze),
            PendingIntent.getBroadcast(
                context,
                SNOOZE_REQUEST_BASE + reminder.id.toInt(),
                carrying(Intent(context, ReminderReceiver::class.java).setAction(Reminders.ACTION_SNOOZE), reminder),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        if (canTick) {
            builder.addAction(
                0,
                context.getString(R.string.reminder_done),
                PendingIntent.getBroadcast(
                    context,
                    reminder.id.toInt(),
                    carrying(Intent(context, ReminderReceiver::class.java).setAction(Reminders.ACTION_DONE), reminder),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        manager.notify(Reminders.notificationId(reminder.id), builder.build())
    }

    /** The note the task is in, opened as an `obsidian://` link opens it, in its own vault. */
    private fun open(
        context: Context,
        reminder: TaskReminder,
        vaultName: String,
    ): PendingIntent {
        fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        val uri = "obsidian://open?vault=${encode(vaultName)}&file=${encode(reminder.path)}"
        return PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Kept apart from the Done buttons' request codes, which are the ids.
        const val SNOOZE_REQUEST_BASE = 1_000_000

        // A rung reminder has left the store -- the store is what is still to
        // come -- so the Done button carries the task itself. It is tapped
        // hours later, long after the process that rang it is gone.
        const val EXTRA_VAULT = "me.parham1995.notes.reminder.VAULT"
        const val EXTRA_PATH = "me.parham1995.notes.reminder.PATH"
        const val EXTRA_TITLE = "me.parham1995.notes.reminder.TITLE"
        const val EXTRA_TEXT = "me.parham1995.notes.reminder.TEXT"
        const val EXTRA_LINE = "me.parham1995.notes.reminder.LINE"

        fun carrying(
            intent: Intent,
            reminder: TaskReminder,
        ): Intent =
            intent
                .putExtra(Reminders.EXTRA_ID, reminder.id)
                .putExtra(EXTRA_VAULT, reminder.vaultId)
                .putExtra(EXTRA_PATH, reminder.path)
                .putExtra(EXTRA_TITLE, reminder.noteTitle)
                .putExtra(EXTRA_TEXT, reminder.text)
                .putExtra(EXTRA_LINE, reminder.line)

        fun rung(intent: Intent): TaskReminder? {
            val path = intent.getStringExtra(EXTRA_PATH) ?: return null
            val text = intent.getStringExtra(EXTRA_TEXT) ?: return null
            return TaskReminder(
                id = intent.getLongExtra(Reminders.EXTRA_ID, 0L),
                vaultId = intent.getLongExtra(EXTRA_VAULT, 0L),
                path = path,
                noteTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                text = text,
                line = intent.getIntExtra(EXTRA_LINE, -1),
                at = 0L,
            )
        }
    }
}
