package me.parham1995.notes.data

import kotlinx.coroutines.flow.Flow
import me.parham1995.notes.data.database.SyncLogDao
import me.parham1995.notes.data.database.SyncLogEntity
import javax.inject.Inject
import javax.inject.Singleton

enum class LogLevel { INFO, WARN, ERROR }

/**
 * A journal of what each sync did, kept on the device.
 *
 * A sync runs in the background, takes minutes on its first run, and fails in
 * ways a single "last error" cannot explain: which step it reached, how many
 * files it moved, whether it fell back to a full listing, how long indexing
 * took. Writing that down means a failure can be diagnosed from the phone
 * rather than from a cable and logcat.
 */
@Singleton
class SyncLog
    @Inject
    constructor(
        private val dao: SyncLogDao,
    ) {
        fun recent(limit: Int = DEFAULT_LIMIT): Flow<List<SyncLogEntity>> = dao.recent(limit)

        suspend fun info(message: String) = write(LogLevel.INFO, message)

        suspend fun warn(message: String) = write(LogLevel.WARN, message)

        suspend fun error(message: String) = write(LogLevel.ERROR, message)

        suspend fun clear() = dao.clear()

        private suspend fun write(
            level: LogLevel,
            message: String,
        ) {
            dao.insert(SyncLogEntity(at = System.currentTimeMillis(), level = level.name, message = message))
            // Bounded on write rather than on a schedule: it is a diagnostic,
            // and an unbounded one would grow for the life of the install.
            if (++writes % TRIM_EVERY == 0) dao.trim(MAX_ENTRIES)
        }

        private var writes = 0

        private companion object {
            const val DEFAULT_LIMIT = 200
            const val MAX_ENTRIES = 500
            const val TRIM_EVERY = 25
        }
    }
