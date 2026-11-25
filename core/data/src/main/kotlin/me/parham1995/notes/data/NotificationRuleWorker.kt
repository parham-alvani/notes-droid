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
import me.parham1995.notes.obsidian.NotificationRule
import java.time.LocalDate
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
            // Before its time is not its run, and a second run on one day rings nothing.
            if (now.toLocalTime() >= rule.rule.at && rules.checkedOn(rule) != today) {
                rules.markChecked(rule, today)
                val text = runCatchingUnlessCancelled { files.readText(vaultId, rule.rule.note) }.getOrNull()
                if (rule.rule.isMissing(text, today)) {
                    runCatching { notify(rule, today, vaults.vault(vaultId)?.label) }
                }
            }
            scheduler.scheduleRule(rule, rules.checkedOn(rule))
            return Result.success()
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
            day: LocalDate,
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
            val open = vaultName?.let { openNote(rule, it) }
            manager.notify(tagOf(rule.rule), rule.vaultId.toInt(), ruleNotification(context, rule.rule, day, open))
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

/** The alert of [rule] on [day]: its title, and what it says is missing. */
internal fun ruleNotification(
    context: Context,
    rule: NotificationRule,
    day: LocalDate,
    open: PendingIntent?,
): Notification {
    val body = rule.body(day)
    return NotificationCompat
        .Builder(context, NotificationRuleWorker.CHANNEL_ID)
        .setContentTitle(rule.alertTitle)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setAutoCancel(true)
        .setContentIntent(open)
        .build()
}
