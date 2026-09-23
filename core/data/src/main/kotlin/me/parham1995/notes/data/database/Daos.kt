package me.parham1995.notes.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState

/**
 * The kinds a person can open from the tree.
 *
 * Markdown is a note and is listed as one; `CONFIG` shapes how notes are
 * presented and belongs to no folder anyone browses.
 */
internal val BROWSABLE_KINDS = listOf(BlobKind.IMAGE, BlobKind.OTHER)

@Dao
interface BlobDao {
    @Upsert
    suspend fun upsert(blob: BlobEntity)

    @Upsert
    suspend fun upsertAll(blobs: List<BlobEntity>)

    @Query("SELECT path, sha FROM blobs WHERE vaultId = :vaultId")
    suspend fun manifestRows(vaultId: Long): List<ManifestRow>

    @Query("SELECT * FROM blobs WHERE vaultId = :vaultId AND path = :path")
    suspend fun byPath(
        vaultId: Long,
        path: String,
    ): BlobEntity?

    @Query("DELETE FROM blobs WHERE vaultId = :vaultId AND path = :path")
    suspend fun deleteByPath(
        vaultId: Long,
        path: String,
    )

    /**
     * `OR REPLACE` because a rename can land on a path the manifest already
     * holds -- a commit that renames A onto B while also deleting B, applied
     * in either order. The row being replaced is the one being overwritten
     * upstream, and the key is the whole row's identity now, so failing here
     * would abort a sync over a file that is about to be correct anyway.
     */
    @Query("UPDATE OR REPLACE blobs SET path = :to WHERE vaultId = :vaultId AND path = :from")
    suspend fun rename(
        vaultId: Long,
        from: String,
        to: String,
    )

    /**
     * One vault's files of a kind. Unscoped, the "Notes" figure on the sync
     * screen added up every repository while the screen around it showed one.
     */
    @Query("SELECT COUNT(*) FROM blobs WHERE vaultId = :vaultId AND kind = :kind")
    fun countOfKind(
        vaultId: Long,
        kind: BlobKind,
    ): Flow<Int>

    /**
     * Files the reader does not parse but can still open -- images, PDFs,
     * recordings, documents.
     *
     * Re-emitted rather than sampled, because a repository of nothing but
     * attachments has no notes to make the tree appear when it syncs.
     *
     * Named kinds rather than "everything that is not markdown", because that
     * swept in the one config file the vault syncs and the browser derived a
     * folder from its path -- so `.obsidian` sat at the top of the tree,
     * offering to open the icon assignments. A kind that shapes how notes are
     * presented is not a file anyone browses to.
     */
    @Query(
        """
        SELECT path FROM blobs
        WHERE vaultId = :vaultId AND kind IN (:kinds)
        ORDER BY path
        """,
    )
    fun attachmentPaths(
        vaultId: Long,
        kinds: List<BlobKind> = BROWSABLE_KINDS,
    ): Flow<List<String>>

    @Query("DELETE FROM blobs")
    suspend fun clear()

    @Query("DELETE FROM blobs WHERE vaultId = :vaultId")
    suspend fun clearVault(vaultId: Long)

    /**
     * Hands every row written before there were several repositories to the
     * one they came from.
     *
     * The migration defaults `vaultId` to zero because it has nothing better
     * to say, and an inserted vault gets id 1. Without this the seeded vault
     * would look at an empty manifest and re-fetch a vault that is already on
     * the device, in full.
     */
    @Query("UPDATE OR REPLACE blobs SET vaultId = :vaultId WHERE vaultId = 0")
    suspend fun adoptOrphans(vaultId: Long)

    @Query("SELECT * FROM blobs WHERE vaultId = :vaultId AND kind = :kind AND localState = :state")
    suspend fun byVaultKindAndState(
        vaultId: Long,
        kind: BlobKind,
        state: LocalState,
    ): List<BlobEntity>
}

/** Just the two columns the planner needs, so a sync does not load whole rows. */
data class ManifestRow(
    val path: String,
    val sha: String,
)

/** How many open tasks one vault holds. */
data class VaultTaskCount(
    val vaultId: Long,
    val count: Int,
)

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = :id")
    suspend fun get(id: Int = SyncStateEntity.SINGLETON_ID): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = :id")
    fun observe(id: Int = SyncStateEntity.SINGLETON_ID): Flow<SyncStateEntity?>

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}

@Dao
interface TaskDao {
    /**
     * Everything still open, in the order a person would work through it:
     * dated first and soonest first, undated last.
     *
     * `actionableOn IS NULL` sorts before the date itself because SQLite puts
     * NULL first otherwise, which would bury 120 overdue tasks under every
     * task that has no date at all.
     */
    @Query(
        """
        SELECT t.id, t.noteId, t.text, t.state, t.section, t.blockIndex, t.line,
               t.actionableOn, t.recurring, n.path AS notePath, n.title AS noteTitle,
               n.vaultId AS vaultId
        FROM tasks t
        JOIN notes n ON n.id = t.noteId
        WHERE t.open = 1 AND n.vaultId = :vaultId
        ORDER BY (t.actionableOn IS NULL), t.actionableOn, n.path, t.ordinal
        """,
    )
    fun open(vaultId: Long): Flow<List<TaskRow>>

    /**
     * The tasks in one note, for ticking one off where it is written rather
     * than only from the list.
     */
    @Query(
        """
        SELECT t.id, t.noteId, t.text, t.state, t.section, t.blockIndex, t.line,
               t.actionableOn, t.recurring, n.path AS notePath, n.title AS noteTitle,
               n.vaultId AS vaultId
        FROM tasks t
        JOIN notes n ON n.id = t.noteId
        WHERE t.noteId = :noteId
        ORDER BY t.ordinal
        """,
    )
    suspend fun byNote(noteId: Long): List<TaskRow>

    /**
     * Counted across every vault, not just the active one.
     *
     * The list is per vault because that is where you go to act on it; the
     * count is what a widget and a notification show, and being late on
     * something in another vault is still being late.
     *
     * Joined to `notes` although nothing here needs a column from it: a task
     * belongs to a note, and one whose note has gone is not anyone's to do.
     * There are no foreign keys to take such rows with the note, and a full
     * reindex once left a copy of every task behind, so counting the table
     * bare inflated the morning's digest by the whole vault's worth.
     */
    @Query(
        """
        SELECT COUNT(*) FROM tasks t JOIN notes n ON n.id = t.noteId
        WHERE t.open = 1 AND t.actionableOn IS NOT NULL AND t.actionableOn < :today
        """,
    )
    suspend fun overdueCount(today: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM tasks t JOIN notes n ON n.id = t.noteId
        WHERE t.open = 1 AND t.actionableOn = :today
        """,
    )
    suspend fun dueTodayCount(today: String): Int

    /**
     * Open tasks per vault, for telling someone their list is empty because
     * they are reading the wrong one.
     */
    @Query(
        """
        SELECT n.vaultId AS vaultId, COUNT(*) AS count FROM tasks t
        JOIN notes n ON n.id = t.noteId
        WHERE t.open = 1
        GROUP BY n.vaultId
        """,
    )
    fun openCountsByVault(): Flow<List<VaultTaskCount>>

    @Query("DELETE FROM tasks WHERE noteId = :noteId")
    suspend fun deleteByNote(noteId: Long)

    @Query("DELETE FROM tasks")
    suspend fun clear()
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vaults ORDER BY ordinal, id")
    fun observe(): Flow<List<VaultEntity>>

    @Query("SELECT * FROM vaults ORDER BY ordinal, id")
    suspend fun all(): List<VaultEntity>

    @Query("SELECT * FROM vaults WHERE id = :id")
    suspend fun byId(id: Long): VaultEntity?

    @Query("SELECT COUNT(*) FROM vaults")
    suspend fun count(): Int

    @Insert
    suspend fun insert(vault: VaultEntity): Long

    @Update
    suspend fun update(vault: VaultEntity)

    @Query("DELETE FROM vaults WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * The queue of edits that have not reached the repository yet.
 *
 * Ordered by when they were made, and flushed in that order: two edits to the
 * same file applied out of order would each be re-applied against the other's
 * result, which is correct but reads as a scrambled history.
 */
@Dao
interface PendingEditDao {
    @Insert
    suspend fun insert(edit: PendingEditEntity): Long

    @Query("SELECT * FROM pending_edits ORDER BY createdAt, id")
    suspend fun all(): List<PendingEditEntity>

    @Query("SELECT * FROM pending_edits ORDER BY createdAt, id")
    fun observe(): Flow<List<PendingEditEntity>>

    @Query("SELECT COUNT(*) FROM pending_edits")
    fun count(): Flow<Int>

    @Update
    suspend fun update(edit: PendingEditEntity)

    @Query("DELETE FROM pending_edits WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending_edits WHERE vaultId = :vaultId")
    suspend fun clearVault(vaultId: Long)
}
