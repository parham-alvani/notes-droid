package me.parham1995.notes.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.LocalState

/**
 * One repository the app reads, as its own vault.
 *
 * Vaults are separate rather than folders inside one tree. Each has its own
 * files, its own index, its own search and its own tasks, and a link in one
 * cannot resolve into another -- which is what a person means by "a different
 * vault" and what mounting them under a shared root could never express.
 *
 * [name] is for reading and can be changed. It does not decide where anything
 * is stored: that is keyed by [id], so renaming a vault does not move two
 * hundred megabytes.
 */
@Entity(tableName = "vaults", indices = [Index("name", unique = true)])
data class VaultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val owner: String,
    val repo: String,
    val branch: String? = null,
    val name: String = "",
    /** `REST` or `SSH`, named rather than typed so the row survives reordering. */
    val transport: String = "REST",
    val ordinal: Int = 0,
    val enabled: Boolean = true,
    val headCommit: String? = null,
    val etagRef: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val filterVersion: Int = 0,
    val indexVersion: Int = 0,
) {
    val label: String get() = name.ifBlank { repo }
}

/**
 * The sync manifest: one row per file the vault contains upstream, whether or
 * not its bytes are on the device.
 *
 * This is what makes the first sync resumable with no extra bookkeeping -- each
 * blob is written and its row upserted in the same transaction, so an
 * interrupted sync simply resumes from whatever the manifest already says.
 */
@Entity(tableName = "blobs", primaryKeys = ["vaultId", "path"])
data class BlobEntity(
    val path: String,
    /**
     * Which repository this came from.
     *
     * Part of the key, not a column beside it. Paths are relative to their own
     * vault, so two vaults holding a `README.md` -- or a `LICENSE`, or an
     * `uploads/` of their own -- both call it the same thing. Keyed by path
     * alone the second vault's row silently replaced the first's, and the
     * migration that made paths relative could not even run: stripping the
     * prefixes collided two rows onto one key and failed, which left the
     * database unopenable and the app unable to start at all.
     */
    val vaultId: Long = 0,
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
    /**
     * Which [me.parham1995.notes.sync.VaultFilter] built this manifest. Zero
     * means "before the filter was versioned", which is treated as out of date.
     */
    val filterVersion: Int = 0,
    /**
     * Which indexer built the tables derived from the notes. A sync only
     * reparses files that changed, so anything the indexer learns to extract
     * stays missing on an existing install until this says otherwise.
     */
    val indexVersion: Int = 0,
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
    indices = [
        // Unique per vault, not globally: two vaults may each hold a README,
        // and they are different notes.
        Index(value = ["vaultId", "path"], unique = true),
        Index("vaultId"),
        Index("parent"),
        Index("slug"),
    ],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Which vault this belongs to. Paths are relative to it. */
    val vaultId: Long,
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
    /**
     * The block this note was last left at.
     *
     * Kept per note rather than for the last one only: reading a vault is
     * moving between a handful of long notes, and coming back to the top of a
     * 89KB one because you followed a link out of it is its own small defeat.
     */
    val scrollIndex: Int = 0,
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
 * One task, lifted out of a note at index time.
 *
 * Stored rather than parsed on demand because the question a task list asks --
 * "what is open across the whole vault, ordered by when it is answerable" -- is
 * one indexed query over this table and 2,400 markdown files otherwise.
 */
@Entity(
    tableName = "tasks",
    indices = [Index("noteId"), Index("open"), Index("actionableOn")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val text: String,
    val state: String,
    /** The heading above it, which in this vault names the project. */
    val section: String,
    /** Index into the note's blocks, so opening it can scroll to the task. */
    val blockIndex: Int,
    val ordinal: Int,
    /**
     * Denormalised from [state] so the list is a single indexed lookup rather
     * than a scan with a CASE in it.
     */
    val open: Boolean,
    /**
     * `due` where there is one, `scheduled` otherwise. Denormalised for the
     * same reason: this is what everything sorts and groups by.
     */
    val actionableOn: String?,
    val scheduled: String?,
    val due: String?,
    val done: String?,
    val recurring: String?,
)

/** A task with the note it lives in, which is what a list actually shows. */
data class TaskRow(
    val id: Long,
    val noteId: Long,
    val text: String,
    val state: String,
    val section: String,
    val blockIndex: Int,
    val actionableOn: String?,
    val notePath: String,
    val noteTitle: String,
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
