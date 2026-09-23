package me.parham1995.notes.data

import android.database.sqlite.SQLiteFullException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.database.SyncLogDao
import me.parham1995.notes.data.database.SyncLogEntity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The journal is written from inside catch blocks, about failures that are
 * often the database's own. It must never be the thing that throws there.
 */
@RunWith(RobolectricTestRunner::class)
class SyncLogTest {
    /** A database with no room left, which is when errors get journalled. */
    private class FullDisk : SyncLogDao {
        var attempts = 0

        override suspend fun insert(entry: SyncLogEntity) {
            attempts++
            throw SQLiteFullException("database or disk is full")
        }

        override fun recent(limit: Int): Flow<List<SyncLogEntity>> = emptyFlow()

        override suspend fun trim(keep: Int) = throw SQLiteFullException("database or disk is full")

        override suspend fun clear() = Unit
    }

    @Test
    fun `a journal that cannot be written does not replace the error it was reporting`() =
        runTest {
            val dao = FullDisk()
            val log = SyncLog(dao)

            val reported =
                runCatching {
                    try {
                        error("the sync's own failure")
                    } catch (failure: IllegalStateException) {
                        log.error("sync failed: ${failure.message}")
                        throw failure
                    }
                }.exceptionOrNull()

            assertThat(reported).isInstanceOf(IllegalStateException::class.java)
            assertThat(reported).hasMessageThat().isEqualTo("the sync's own failure")
            assertThat(dao.attempts).isEqualTo(1)
        }
}
