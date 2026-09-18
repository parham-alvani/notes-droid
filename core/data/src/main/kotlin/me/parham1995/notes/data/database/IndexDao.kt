package me.parham1995.notes.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Everything one note contributes to the index, written as a unit. */
data class NoteWrite(
    val note: NoteEntity,
    /** `noteId` is assigned during the write. */
    val headings: List<HeadingEntity>,
    /** `srcId` is assigned during the write. */
    val links: List<LinkEntity>,
    /** `noteId` is assigned during the write. */
    val tasks: List<TaskEntity>,
)

/**
 * The indexer's writes, batched into real transactions.
 *
 * Room's `withTransaction` extension goes through the old SupportSQLite path
 * and throws outright when the database is configured with a driver instead --
 * "no SupportSQLiteOpenHelper.Factory was configured" -- which killed indexing
 * immediately after a successful sync. `@Transaction` is the form that works
 * with the driver API, and batching still matters: a commit per note is an
 * fsync per note, which turns a seconds-long index into a minutes-long one.
 */
@Dao
abstract class IndexDao {
    @Upsert
    abstract suspend fun upsertNote(note: NoteEntity): Long

    @Query("SELECT id FROM notes WHERE path = :path")
    abstract suspend fun idOf(path: String): Long?

    @Query("DELETE FROM headings WHERE noteId = :noteId")
    abstract suspend fun deleteHeadings(noteId: Long)

    @Insert
    abstract suspend fun insertHeadings(rows: List<HeadingEntity>)

    @Query("DELETE FROM links WHERE srcId = :srcId")
    abstract suspend fun deleteLinks(srcId: Long)

    @Insert
    abstract suspend fun insertLinks(rows: List<LinkEntity>)

    @Query("DELETE FROM tasks WHERE noteId = :noteId")
    abstract suspend fun deleteTasks(noteId: Long)

    @Insert
    abstract suspend fun insertTasks(rows: List<TaskEntity>)

    @Query("UPDATE links SET targetId = :targetId WHERE id = :id")
    abstract suspend fun setTarget(
        id: Long,
        targetId: Long?,
    )

    /** Writes a batch and returns each note's id, in the order given. */
    @Transaction
    open suspend fun writeBatch(batch: List<NoteWrite>): List<Long> =
        batch.map { write ->
            val existing = idOf(write.note.path) ?: 0
            val id = upsertNote(write.note.copy(id = existing))

            deleteHeadings(id)
            if (write.headings.isNotEmpty()) insertHeadings(write.headings.map { it.copy(noteId = id) })

            deleteLinks(id)
            if (write.links.isNotEmpty()) insertLinks(write.links.map { it.copy(srcId = id) })

            deleteTasks(id)
            if (write.tasks.isNotEmpty()) insertTasks(write.tasks.map { it.copy(noteId = id) })

            id
        }

    /** Attaches resolved links to their targets, nulls included. */
    @Transaction
    open suspend fun applyTargets(targets: List<Pair<Long, Long?>>) {
        targets.forEach { (linkId, targetId) -> setTarget(linkId, targetId) }
    }
}
