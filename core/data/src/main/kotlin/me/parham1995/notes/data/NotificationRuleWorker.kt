package me.parham1995.notes.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.withTimeoutOrNull
import me.parham1995.notes.data.database.TaskDao
import me.parham1995.notes.data.database.toQueryTask
import me.parham1995.notes.markdown.TasksQuery
import me.parham1995.notes.obsidian.NotificationRule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One notification rule, at its time: when the note has no heading for
 * today, a notification that says so and opens the note.
 *
 * A sync first, when the phone may sync now, so a heading written at the
 * desk an hour ago counts; bounded, because an alert that waits on the
 * network is an alert that does not arrive. Then the note is read from disk
 * as the sync left it. Nothing is written to the vault.
 *
 * Each rule is periodic work of its own, set outright to the next `at` by
 * [SyncScheduler.scheduleRules]. The run puts its own next one at tomorrow's
 * `at` again: a daily period drifts by however late each run was.
 */
@HiltWorker
class NotificationRuleWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted params: WorkerParameters,
        private val rules: NotificationRules,
        private val settings: SettingsStore,
        private val files: VaultFileStore,
        private val vaults: VaultRepository,
        private val sync: SyncRepository,
        private val scheduler: SyncScheduler,
        private val history: NoteHistoryRepository,
        private val tasks: TaskDao,
        private val statuses: TaskStatusRules,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val vaultId = inputData.getLong(KEY_VAULT, 0L)
            val id = inputData.getString(KEY_RULE) ?: return Result.success()
            val current = settings.current()
            syncFirst(current)
            // After the sync: the vault's file may have changed the rule, or taken it away.
            val rule =
                rules.one(vaultId, id, current.fromVault.decline)?.let { VaultRule(vaultId, it) }
                    ?: return Result.success()
            val now = ZonedDateTime.now()
            val today = now.toLocalDate()
            // Before its time is not its run, a second run on one day rings nothing, and
            // a day the rule does not name is checked and passed over.
            if (now.toLocalTime() >= rule.rule.at && rules.checkedOn(rule) != today) {
                rules.markChecked(rule, today)
                if (rule.rule.ringsOn(today)) {
                    val body = runCatchingUnlessCancelled { bodyOf(rule, today) }.getOrNull()
                    if (body != null) runCatching { notify(rule, body, vaults.vault(vaultId)?.label) }
                }
            }
            scheduler.scheduleRule(rule, rules.checkedOn(rule))
            return Result.success()
        }

        /** What the alert of [rule] says on [day], or null when it has nothing to say: the note is as it should be. */
        private suspend fun bodyOf(
            rule: VaultRule,
            day: LocalDate,
        ): String? {
            val it = rule.rule
            val query = it.query
            return when {
                query != null -> {
                    // Open tasks only: the rule rings for what is still to do.
                    val vaultStatuses = statuses.forVault(rule.vaultId)
                    val found =
                        TasksQuery
                            .parse(query.joinToString("\n"))
                            .run(tasks.openInVault(rule.vaultId), day) { row -> row.toQueryTask(vaultStatuses) }
                    it.queryBody(found.map { row -> row.text })
                }
                it.quietDays != null -> {
                    val changed = lastChange(rule.vaultId, it.note)
                    if (it.isQuiet(changed, day)) it.quietBody(changed, day) else null
                }
                else -> {
                    val text = files.readText(rule.vaultId, it.note)
                    if (it.isMissing(text, day)) it.body(day) else null
                }
            }
        }

        /**
         * The day [path] last changed: its last commit in the vault's history,
         * else the file's modified time; null when there is no such note.
         */
        private suspend fun lastChange(
            vaultId: Long,
            path: String,
        ): LocalDate? {
            val zone = ZoneId.systemDefault()
            // A note that is not there has no last change, whatever its history says.
            val file = files.fileFor(vaultId, path).takeIf { it.isFile } ?: return null
            runCatchingUnlessCancelled { history.history(vaultId, path).firstOrNull()?.date }
                .getOrNull()
                ?.let { return it.atZone(zone).toLocalDate() }
            return Instant.ofEpochMilli(file.lastModified()).atZone(zone).toLocalDate()
        }

        /** A sync, when the app syncs on its own and the network it may use is there; a minute at most. */
        private suspend fun syncFirst(current: VaultSettings) {
            if (!current.backgroundSync || !current.isConfigured) return
            val network = scheduler.network()
            if (!network.validated || (current.syncOnWifiOnly && !network.unmetered)) return
            withTimeoutOrNull(SYNC_TIMEOUT_MS) {
                runCatchingUnlessCancelled { sync.sync() }
                    .onSuccess { runCatching { SyncWorker.afterSync?.invoke() } }
            }
        }

        private fun notify(
            rule: VaultRule,
            body: String,
            vaultName: String?,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.rule_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            // A query rule opens the tasks, as the digest does; the others their note.
            val open = if (rule.rule.query != null) openTasks() else vaultName?.let { openNote(rule, it) }
            manager.notify(tagOf(rule.rule), rule.vaultId.toInt(), ruleNotification(context, rule.rule, body, open))
        }

        /**
         * Opens the note as an `obsidian://` link opens it, in its own vault.
         * Built from the launcher intent rather than by naming the activity,
         * because this module knows nothing about the UI.
         */
        private fun openNote(
            rule: VaultRule,
            vaultName: String,
        ): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            val uri = "obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(rule.rule.note)}"
            launch
                .setAction(Intent.ACTION_VIEW)
                .setData(uri.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                rule.workName.hashCode(),
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** The app on the task list, as the digest opens it. */
        private fun openTasks(): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.putExtra(TaskDigestWorker.EXTRA_OPEN_TASKS, true)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                inputData.getString(KEY_RULE).hashCode(),
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        companion object {
            /** Every rule's job carries this tag, so the ones no file says any more can be found. */
            const val TAG = "notification-rule"

            /** The rules' own channel: a person may want the diary loud and the digest silent. */
            const val CHANNEL_ID = "notification-rules"

            const val KEY_VAULT = "vault"
            const val KEY_RULE = "rule"

            private const val SYNC_TIMEOUT_MS = 60_000L

            /** The notification's tag, as the spec names it: a day's alert replaces the day before's. */
            fun tagOf(rule: NotificationRule): String = "rule.${rule.id}"
        }
    }

/** The alert of [rule]: its title, and [body], what it says. */
internal fun ruleNotification(
    context: Context,
    rule: NotificationRule,
    body: String,
    open: PendingIntent?,
): Notification =
    NotificationCompat
        .Builder(context, NotificationRuleWorker.CHANNEL_ID)
        .setContentTitle(rule.alertTitle)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setSmallIcon(R.drawable.ic_stat_daftar)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setContentIntent(open)
        .build()
