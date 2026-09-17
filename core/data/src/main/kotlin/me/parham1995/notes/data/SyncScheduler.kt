package me.parham1995.notes.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.time.Duration
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
         * Pull-to-refresh and the periodic job enqueue the same unique work, so
         * a manual refresh during a running sync joins it rather than starting
         * a second one.
         */
        fun syncNow(wifiOnly: Boolean = false) {
            workManager.enqueueUniqueWork(
                SyncWorker.UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(constraints(wifiOnly))
                    .build(),
            )
        }

        /**
         * Best-effort background refresh. Custom ROMs are aggressive about
         * killing background work, so pull-to-refresh stays the primary path
         * and this is a convenience rather than a guarantee.
         */
        fun schedulePeriodic(wifiOnly: Boolean = false) {
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofHours(PERIOD_HOURS))
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

        fun observe(): Flow<List<WorkInfo>> = workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_WORK)

        private fun constraints(wifiOnly: Boolean) =
            Constraints
                .Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

        private companion object {
            const val PERIODIC_WORK = "vault-sync-periodic"
            const val PERIOD_HOURS = 6L
        }
    }
