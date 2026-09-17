package me.parham1995.notes.sync

/** What a path is, as far as syncing is concerned. */
enum class BlobKind {
    MARKDOWN,
    IMAGE,
    OTHER,
}

/** Whether the bytes for a path are actually on the device. */
enum class LocalState {
    /** Known to exist upstream, deliberately not downloaded (images, by default). */
    ABSENT,

    /** Present on disk and matching [VaultEntry.sha]. */
    DOWNLOADED,

    /** Present on disk but the upstream content moved on. */
    STALE,
}

/** One file in the vault, as the remote describes it. */
data class VaultEntry(
    val path: String,
    val sha: String,
    val size: Long,
    val kind: BlobKind,
)

/** A path whose content did not change, only its name. Costs no bytes to apply. */
data class Rename(
    val from: String,
    val to: String,
    val sha: String,
)

/**
 * The work one sync has to do. Produced by [SyncPlanner] from a local manifest
 * and whatever the remote reported, and consumed by whatever actually moves
 * bytes -- so the interesting logic stays pure and testable.
 */
data class SyncPlan(
    /** The commit the device is currently at; null on a first sync. */
    val baseCommit: String?,
    val headCommit: String,
    val adds: List<VaultEntry> = emptyList(),
    val modifies: List<VaultEntry> = emptyList(),
    val renames: List<Rename> = emptyList(),
    val deletes: List<String> = emptyList(),
    val unchanged: Int = 0,
    /** ETag for the ref, to be stored and replayed on the next refresh. */
    val etagRef: String? = null,
) {
    /** Paths whose bytes must actually be fetched. */
    val downloads: List<VaultEntry> get() = adds + modifies

    val isEmpty: Boolean
        get() = adds.isEmpty() && modifies.isEmpty() && renames.isEmpty() && deletes.isEmpty()

    /** Total bytes this plan will pull, for a progress estimate. */
    val downloadBytes: Long get() = downloads.sumOf { it.size }
}

/**
 * The two transports the app can sync over. `RestVaultSync` is the default;
 * a git-over-SSH implementation is planned behind the same interface.
 */
interface VaultSync {
    /** Work out what changed, without moving any file content. */
    suspend fun plan(base: SyncBase): SyncPlan

    /** Apply [plan], reporting progress as it goes. */
    suspend fun apply(
        plan: SyncPlan,
        sink: VaultSink,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    )
}

/** What the device already has, handed to [VaultSync.plan]. */
data class SyncBase(
    val commit: String?,
    /** path to blob sha, for everything the device is tracking. */
    val manifest: Map<String, String>,
    val etagRef: String? = null,
)

/** Where synced bytes land. Implemented on the storage side. */
interface VaultSink {
    /** Store [bytes] at [path]. Implies recording it as [LocalState.DOWNLOADED]. */
    suspend fun write(
        path: String,
        bytes: ByteArray,
        sha: String,
    )

    /**
     * Note that [entry] exists upstream without fetching it. This is how images
     * get into the manifest under the on-demand policy: the path and sha are
     * known, so a note that embeds one can fetch it later, but a default
     * install carries only the markdown.
     */
    suspend fun record(
        entry: VaultEntry,
        state: LocalState,
    )

    suspend fun move(
        from: String,
        to: String,
    )

    suspend fun delete(path: String)
}
