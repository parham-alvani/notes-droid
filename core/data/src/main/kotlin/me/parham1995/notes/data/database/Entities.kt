package me.parham1995.notes.data.database

import androidx.room.Entity
import androidx.room.Index
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

/**
 * One note. [id] is the navigation key everywhere in the app: paths contain
 * spaces, `#`, and non-Latin script, and the longest runs past 200 characters,
 * so routing by path is a class of bug an integer sidesteps entirely.
 */
@Entity(
    tableName = "notes",
    indices = [Index("path", unique = true), Index("parent"), Index("slug")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    /** Containing directory, for the browser's one-level-at-a-time queries. */
    val parent: String,
    /** File name without the extension. */
    val name: String,
    /** Case-folded, NFC-normalised [name], for lookups. */
    val slug: String,
    val title: String,
    val blobSha: String,
    val size: Long,
    /** True when this note is its folder's index -- `X/X.md`. */
    val isFolderNote: Boolean,
    val isRtl: Boolean,
    /** Cheap hints so the reader can show a placeholder for costly blocks. */
    val hasMermaid: Boolean,
    val hasMath: Boolean,
    val indexedAt: Long,
    val openedAt: Long? = null,
)

@Entity(
    tableName = "links",
    indices = [Index("srcId"), Index("targetId")],
)
data class LinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val srcId: Long,
    val kind: String,
    val rawTarget: String,
    val alias: String? = null,
    val heading: String? = null,
    /** Null when the link is broken -- rendered differently, not hidden. */
    val targetId: Long? = null,
    /**
     * The surrounding line. Denormalised on purpose: backlinks must never have
     * to re-read and re-parse the source note to show context.
     */
    val context: String,
    val ordinal: Int,
)

@Entity(
    tableName = "headings",
    indices = [Index("noteId")],
)
data class HeadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val level: Int,
    val text: String,
    val slug: String,
    val ordinal: Int,
    /** Index into the flattened block list, so an outline tap can scroll. */
    val blockIndex: Int,
)

/**
 * One line of the sync journal.
 *
 * A sync is long, runs in the background and fails in ways a single "last
 * error" string cannot explain -- which step, how many files, how long, whether
 * it fell back. Keeping the trail on the device means diagnosing it does not
 * need a cable and logcat.
 */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val level: String,
    val message: String,
)
