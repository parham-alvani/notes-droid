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

    /**
     * Scoped, and it has to be. Two vaults may each hold a note at the same
     * path; looking one up by path alone finds whichever vault got there first
     * and then overwrites it, which is a vault silently eating another's notes.
     */
    @Query("SELECT id FROM notes WHERE vaultId = :vaultId AND path = :path")
    abstract suspend fun idOf(
        vaultId: Long,
        path: String,
    ): Long?

    /**
     * What a person has done with a note, which a reindex has no business
     * forgetting -- see [writeBatch].
     */
    @Query("SELECT id, openedAt, scrollIndex FROM notes WHERE vaultId = :vaultId AND path = :path")
    abstract suspend fun stateOf(
        vaultId: Long,
        path: String,
    ): NoteState?

    @Query("DELETE FROM notes WHERE id = :id")
    abstract suspend fun deleteNote(id: Long)

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

    /**
     * Writes a batch and returns each note's id, in the order given.
     *
     * **`@Upsert` returns -1 when it resolved to an update, not the row's id.**
     * Everything below keys on that id, so taking it at face value wrote every
     * heading, link and task of an *existing* note against `noteId = -1` --
     * rows no query will ever find, while the note itself updated perfectly.
     * A note therefore kept the tasks and backlinks it had when it was first
     * indexed, however often it changed afterwards, and only a full reindex
     * put it right. That was invisible because a full reindex runs whenever
     * [me.parham1995.notes.data.VaultIndexer.VERSION] moves, which is most
     * releases.
     *
     * The row is updated in place, keeping its id, when it was last opened and
     * where it was left. The entity the indexer builds knows none of those --
     * it has just parsed a file -- and upserting it as it stands reset them on
     * every change: Recents emptied and every note reopened at the top after
     * each sync, and a full reindex reissued every id as well.
     */
    @Transaction
    open suspend fun writeBatch(batch: List<NoteWrite>): List<Long> =
        batch.map { write ->
            val state = stateOf(write.note.vaultId, write.note.path)
            val note =
                if (state == null) {
                    write.note.copy(id = 0)
                } else {
                    write.note.copy(id = state.id, openedAt = state.openedAt, scrollIndex = state.scrollIndex)
                }
            val existing = state?.id ?: 0
            val id = upsertNote(note).takeIf { it > 0 } ?: existing

            deleteHeadings(id)
            if (write.headings.isNotEmpty()) insertHeadings(write.headings.map { it.copy(noteId = id) })

            deleteLinks(id)
            if (write.links.isNotEmpty()) insertLinks(write.links.map { it.copy(srcId = id) })

            deleteTasks(id)
            if (write.tasks.isNotEmpty()) insertTasks(write.tasks.map { it.copy(noteId = id) })

            id
        }

    /**
     * Drops notes by path, with every row derived from them, and answers the
     * ids that went so the search index -- which is not one of Room's tables
     * -- can drop them too.
     *
     * There are no foreign keys to cascade, so each derived table is cleared
     * by hand; one left out is rows that nothing will ever delete.
     */
    @Transaction
    open suspend fun removeNotes(
        vaultId: Long,
        paths: Collection<String>,
    ): List<Long> =
        paths.mapNotNull { path ->
            idOf(vaultId, path)?.also { id ->
                deleteLinks(id)
                deleteHeadings(id)
                deleteTasks(id)
                deleteNote(id)
            }
        }

    /** Attaches resolved links to their targets, nulls included. */
    @Transaction
    open suspend fun applyTargets(targets: List<Pair<Long, Long?>>) {
        targets.forEach { (linkId, targetId) -> setTarget(linkId, targetId) }
    }
}

/** The part of a note that belongs to the reader rather than the file. */
data class NoteState(
    val id: Long,
    val openedAt: Long?,
    val scrollIndex: Int,
)
