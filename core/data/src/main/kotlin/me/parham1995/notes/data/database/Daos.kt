package me.parham1995.notes.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState

@Dao
interface BlobDao {
    @Upsert
    suspend fun upsert(blob: BlobEntity)

    @Upsert
    suspend fun upsertAll(blobs: List<BlobEntity>)

    @Query("SELECT path, sha FROM blobs")
    suspend fun manifestRows(): List<ManifestRow>

    @Query("SELECT * FROM blobs WHERE path = :path")
    suspend fun byPath(path: String): BlobEntity?

    @Query("SELECT * FROM blobs WHERE kind = :kind AND localState = :state")
    suspend fun byKindAndState(
        kind: BlobKind,
        state: LocalState,
    ): List<BlobEntity>

    @Query("DELETE FROM blobs WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("UPDATE blobs SET path = :to WHERE path = :from")
    suspend fun rename(
        from: String,
        to: String,
    )

    @Query("SELECT COUNT(*) FROM blobs WHERE kind = :kind")
    fun countOfKind(kind: BlobKind): Flow<Int>

    @Query("SELECT COALESCE(SUM(size), 0) FROM blobs WHERE localState = :state")
    suspend fun bytesInState(state: LocalState): Long

    @Query("DELETE FROM blobs")
    suspend fun clear()
}

/** Just the two columns the planner needs, so a sync does not load whole rows. */
data class ManifestRow(
    val path: String,
    val sha: String,
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
