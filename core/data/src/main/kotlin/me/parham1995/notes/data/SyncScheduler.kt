package me.parham1995.notes.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import me.parham1995.notes.data.calendar.CalendarWatchWorker
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val workManager = WorkManager.getInstance(context)
        private val connectivity = context.getSystemService(ConnectivityManager::class.java)

        /**
         * The network the phone is on now, as the sync's constraints see it.
         *
         * WorkManager holds a sync until the network is validated -- one that
         * has reached the internet, not one that is merely joined -- so a phone
         * on Wi-Fi with no way out queues a sync and runs nothing. Asked here
         * so the screen that asked for the sync can say it is waiting.
         */
        fun network(): NetworkNow {
            val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            return NetworkNow(
                validated =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
            )
        }

        /**
         * Starts a sync now, replacing anything already queued.
         *
         * REPLACE rather than KEEP, and the difference is not subtle. WorkManager
         * persists its queue across process death, so force-stopping mid-sync
         * leaves work that is neither running nor finished. Under KEEP every
         * later tap on Sync was silently dropped in favour of that corpse, and
         * the UI -- which called anything unfinished "syncing" -- showed a
         * spinner forever. An explicit tap means now.
         */
        fun syncNow(wifiOnly: Boolean = false) {
            workManager.enqueueUniqueWork(
                SyncWorker.UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints(wifiOnly))
                    // A tap should start now, not when the scheduler feels
                    // like it. Below API 31 this runs as a foreground service
                    // that WorkManager starts itself -- which is allowed,
                    // because the app is in front when the tap happens.
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(workDataOf(SyncWorker.KEY_USER_INITIATED to true))
                    .build(),
            )
        }

        /**
         * One sync, [delay] from now, for when GitHub has said when it will
         * take requests again.
         *
         * Its own unique name: enqueuing the sync's own unique work with
         * REPLACE from inside that work cancels the job doing the enqueuing.
         */
        fun syncAfter(
            delay: Duration,
            wifiOnly: Boolean = false,
        ) {
            workManager.enqueueUniqueWork(
                RESUME_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints(wifiOnly))
                    .setInitialDelay(delay)
                    .build(),
            )
        }

        /** Stops a sync that is running or waiting to retry. */
        fun cancel() {
            workManager.cancelUniqueWork(SyncWorker.UNIQUE_WORK)
            workManager.cancelUniqueWork(RESUME_WORK)
        }

        /**
         * The background refresh.
         *
         * Deliberately not expedited and deliberately without a notification:
         * this is an ordinary scheduled job, which Android is happy to run in
         * the background. Asking for a foreground service here is what threw
         * ForegroundServiceStartNotAllowedException on every run -- an app in
         * the background is not allowed to start one, and it did not need one.
         *
         * Custom ROMs are still aggressive about deferring scheduled work, so
         * pull-to-refresh remains the path that always works.
         */
        fun schedulePeriodic(
            wifiOnly: Boolean = false,
            intervalHours: Long = PERIOD_HOURS,
        ) {
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(intervalHours))
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).build(),
            )
        }

        fun cancelPeriodic() = workManager.cancelUniqueWork(PERIODIC_WORK)

        /**
         * Runs the calendar export once, a minute after the next change to
         * any event on the phone -- see [CalendarWatchWorker], which arms the
         * one after. The minute batches: a Google sync that touches fifty
         * events is one run. Ten minutes at most, so a calendar that never
         * stops changing is still written.
         *
         * KEEP from the app, so a launch does not cancel a run under way.
         */
        fun watchCalendar(
            wifiOnly: Boolean,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        ) {
            val constraints =
                Constraints
                    .Builder()
                    .addContentUriTrigger(CalendarContract.Events.CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(Duration.ofMinutes(1))
                    .setTriggerContentMaxDelay(Duration.ofMinutes(CALENDAR_MAX_DELAY_MINUTES))
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            workManager.enqueueUniqueWork(
                CalendarWatchWorker.UNIQUE_WORK,
                policy,
                OneTimeWorkRequestBuilder<CalendarWatchWorker>().setConstraints(constraints).build(),
            )
        }

        fun stopWatchingCalendar() = workManager.cancelUniqueWork(CalendarWatchWorker.UNIQUE_WORK)

        /**
         * Arms the daily task digest for the next occurrence of [hour].
         *
         * Periodic work rather than a worker that re-arms itself: enqueuing
         * unique work with REPLACE from inside the job that *is* that unique
         * work cancels the job doing the enqueuing. Periodic work drifts
         * under Doze instead, which is the lesser problem -- and re-arming
         * on every launch and every change of hour puts it back.
         *
         * The next run is set outright, not as an initial delay. UPDATE keeps
         * the work's original enqueue time, so a delay is measured from
         * whenever the digest was first switched on, and once the work has
         * run its first period the delay is never read again: choosing a new
         * hour ran the digest on the spot and left it due a day later, at
         * the minute of the tap. The override names the instant and works
         * under UPDATE; the day after it, the period takes over from there.
         */
        fun scheduleDigest(hour: Int) {
            workManager.enqueueUniquePeriodicWork(
                TaskDigestWorker.UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<TaskDigestWorker>(Duration.ofDays(1))
                    .setNextScheduleTimeOverride(nextAt(hour))
                    .build(),
            )
        }

        fun cancelDigest() = workManager.cancelUniqueWork(TaskDigestWorker.UNIQUE_WORK)

        /**
         * The digest once, now, without touching the daily schedule. For
         * seeing what it says from a terminal rather than waiting for the
         * evening; the worker still checks the setting before posting.
         */
        fun digestNow() {
            workManager.enqueueUniqueWork(
                DIGEST_NOW_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TaskDigestWorker>().build(),
            )
        }

        /**
         * Arms each notification rule for its next check, and stops the ones
         * no vault says any more. Run on every launch and whenever the vault
         * settings change. [checkedOn] is the day a rule was last checked.
         *
         * Periodic work per rule, set outright to the next `at` as the digest
         * is; see [scheduleDigest] for why it is not an initial delay.
         */
        suspend fun scheduleRules(
            rules: List<VaultRule>,
            checkedOn: (VaultRule) -> LocalDate?,
        ) {
            rules.forEach { scheduleRule(it, checkedOn(it)) }
            val kept = rules.map { it.workName }.toSet()
            workManager
                .getWorkInfosByTagFlow(NotificationRuleWorker.TAG)
                .first()
                .filterNot { it.state.isFinished }
                .flatMap { it.tags }
                .filter { it.startsWith(NotificationRuleWorker.TAG + ".") && it !in kept }
                .forEach { workManager.cancelUniqueWork(it) }
        }

        /** Arms [rule] for its next check ([nextCheck]). */
        fun scheduleRule(
            rule: VaultRule,
            checkedOn: LocalDate?,
        ) {
            val next = nextCheck(rule.rule.at, ZonedDateTime.now(), checkedOn)
            workManager.enqueueUniquePeriodicWork(
                rule.workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<NotificationRuleWorker>(Duration.ofDays(1))
                    .setNextScheduleTimeOverride(next.toInstant().toEpochMilli())
                    .addTag(NotificationRuleWorker.TAG)
                    .addTag(rule.workName)
                    .setInputData(
                        workDataOf(
                            NotificationRuleWorker.KEY_VAULT to rule.vaultId,
                            NotificationRuleWorker.KEY_RULE to rule.rule.id,
                        ),
                    ).build(),
            )
        }

        /** The next time the clock reads [hour] o'clock, in epoch milliseconds. */
        private fun nextAt(hour: Int): Long {
            val now = ZonedDateTime.now()
            val today =
                now
                    .withHour(hour.coerceIn(0, LAST_HOUR))
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0)
            val next = if (today.isAfter(now)) today else today.plusDays(1)
            return next.toInstant().toEpochMilli()
        }

        fun observe(): Flow<List<WorkInfo>> = workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_WORK)

        /**
         * Whether a sync is running now: one asked for, or the periodic one.
         * [observe] sees only the first, so a background sync would show as
         * idle to anything that asks it alone. RUNNING only, for the reason
         * the screens give: a job waiting for the network is not syncing.
         */
        fun running(): Flow<Boolean> =
            combine(observe(), workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK)) { asked, periodic ->
                (asked + periodic).any { it.state == WorkInfo.State.RUNNING }
            }

        private fun constraints(wifiOnly: Boolean) =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

        companion object {
            /**
             * When a rule checked at [at] is next checked, seen at [now]:
             * today's `at` when it is still to come, else tomorrow's. A rule
             * not checked today ([checkedOn]) whose `at` passed less than
             * [LATE_MINUTES] ago is checked now, so that arming it again
             * just after its time -- a launch, a sync that brought the file
             * -- does not skip the day.
             */
            fun nextCheck(
                at: LocalTime,
                now: ZonedDateTime,
                checkedOn: LocalDate?,
            ): ZonedDateTime {
                val today = now.with(at).withSecond(0).withNano(0)
                return when {
                    today.isAfter(now) -> today
                    checkedOn != now.toLocalDate() && now.isBefore(today.plusMinutes(LATE_MINUTES)) -> now
                    else -> today.plusDays(1)
                }
            }

            /** How late a rule's check may still be on its day. */
            const val LATE_MINUTES = 10L

            private const val PERIODIC_WORK = "vault-sync-periodic"
            private const val RESUME_WORK = "vault-sync-after-limit"
            private const val DIGEST_NOW_WORK = "task-digest-now"
            private const val PERIOD_HOURS = 6L
            private const val CALENDAR_MAX_DELAY_MINUTES = 10L
            private const val LAST_HOUR = 23
        }
    }

/** The phone's network at one moment: whether it reaches the internet, and whether it is billed. */
data class NetworkNow(
    val validated: Boolean,
    val unmetered: Boolean,
)
