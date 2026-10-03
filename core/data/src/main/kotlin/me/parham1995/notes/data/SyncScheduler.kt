package me.parham1995.notes.data

import android.content.Context
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
import java.time.Duration
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

        private fun constraints(wifiOnly: Boolean) =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

        private companion object {
            const val PERIODIC_WORK = "vault-sync-periodic"
            const val RESUME_WORK = "vault-sync-after-limit"
            const val DIGEST_NOW_WORK = "task-digest-now"
            const val PERIOD_HOURS = 6L
            const val LAST_HOUR = 23
        }
    }
