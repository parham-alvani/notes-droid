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

    @Query("SELECT * FROM blobs WHERE kind = :kind AND localState = :state")
    suspend fun byKindAndState(
        kind: BlobKind,
        state: LocalState,
    ): List<BlobEntity>

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

    @Query("SELECT COUNT(*) FROM blobs WHERE kind = :kind")
    fun countOfKind(kind: BlobKind): Flow<Int>

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

    @Query("SELECT COALESCE(SUM(size), 0) FROM blobs WHERE localState = :state")
    suspend fun bytesInState(state: LocalState): Long

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
        SELECT t.id, t.noteId, t.text, t.state, t.section, t.blockIndex, t.actionableOn,
               n.path AS notePath, n.title AS noteTitle
        FROM tasks t
        JOIN notes n ON n.id = t.noteId
        WHERE t.open = 1 AND n.vaultId = :vaultId
        ORDER BY (t.actionableOn IS NULL), t.actionableOn, n.path, t.ordinal
        """,
    )
    fun open(vaultId: Long): Flow<List<TaskRow>>

    /**
     * Counted across every vault, not just the active one.
     *
     * The list is per vault because that is where you go to act on it; the
     * count is what a widget and a notification show, and being late on
     * something in another vault is still being late.
     */

    @Query("SELECT COUNT(*) FROM tasks WHERE open = 1 AND actionableOn IS NOT NULL AND actionableOn < :today")
    suspend fun overdueCount(today: String): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE open = 1 AND actionableOn = :today")
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
