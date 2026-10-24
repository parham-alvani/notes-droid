package me.parham1995.notes.data.calendar

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SyncLog
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.describeChain
import me.parham1995.notes.data.runCatchingUnlessCancelled

/**
 * The week notes, written about a minute after an event on the phone
 * changes, so they are as fresh as the calendar and not as the last sync.
 * Only the export runs: the pull of the rest of the vault stays on the sync's
 * own schedule.
 *
 * A content trigger fires once, so every run arms the next one. It is armed
 * with APPEND_OR_REPLACE, which queues it behind this run: REPLACE from
 * inside the unique work cancels the job doing the enqueuing. For the same
 * reason this never returns failure -- a failed run fails whatever is
 * appended to it, and the watch would stop. A write that could not go up is
 * in the queue regardless, and the next sync sends it.
 */
@HiltWorker
class CalendarWatchWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val export: CalendarExport,
        private val settings: SettingsStore,
        private val scheduler: SyncScheduler,
        private val log: SyncLog,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val current = settings.current()
            // The watch outlives the setting being switched off between runs.
            if (!current.calendar.exports) return Result.success()
            runCatchingUnlessCancelled { export.run() }
                .onFailure { log.warn("calendar export after a change failed: " + it.describeChain()) }
            scheduler.watchCalendar(current.syncOnWifiOnly, ExistingWorkPolicy.APPEND_OR_REPLACE)
            return Result.success()
        }

        companion object {
            const val UNIQUE_WORK = "calendar-watch"
        }
    }
