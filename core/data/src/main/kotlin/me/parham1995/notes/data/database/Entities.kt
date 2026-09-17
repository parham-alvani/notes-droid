package me.parham1995.notes.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState

/**
 * The sync manifest: one row per file the vault contains upstream, whether or
 * not its bytes are on the device.
 *
 * This is what makes the first sync resumable with no extra bookkeeping -- each
 * blob is written and its row upserted in the same transaction, so an
 * interrupted sync simply resumes from whatever the manifest already says.
 */
@Entity(tableName = "blobs")
data class BlobEntity(
    @PrimaryKey val path: String,
    val sha: String,
    val size: Long,
    val kind: BlobKind,
    val localState: LocalState,
)

/**
 * Single-row table holding where the device is up to. The ETag is the reason a
 * quiet refresh costs one unbilled request.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val headCommit: String? = null,
    val etagRef: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

/**
 * Enums are stored by name rather than ordinal so that adding or reordering a
 * constant later cannot silently reinterpret existing rows.
 */
class Converters {
    @TypeConverter
    fun blobKindToString(value: BlobKind): String = value.name

    @TypeConverter
    fun stringToBlobKind(value: String): BlobKind = BlobKind.valueOf(value)

    @TypeConverter
    fun localStateToString(value: LocalState): String = value.name

    @TypeConverter
    fun stringToLocalState(value: String): LocalState = LocalState.valueOf(value)
}
