package me.parham1995.notes.sync

/** How a path changed between two commits, as the compare endpoint reports it. */
enum class ChangeStatus {
    ADDED,
    MODIFIED,
    REMOVED,
    RENAMED,
    COPIED,
    CHANGED,
    UNCHANGED,
    ;

    companion object {
        fun parse(raw: String): ChangeStatus =
            when (raw) {
                "added" -> ADDED
                "modified" -> MODIFIED
                "removed" -> REMOVED
                "renamed" -> RENAMED
                "copied" -> COPIED
                "changed" -> CHANGED
                else -> UNCHANGED
            }
    }
}

/**
 * A compare between two commits: how they relate, and the files that differ.
 *
 * [status] matters as much as [files]. The endpoint is a three-dot compare, so
 * the files are the diff from the *merge base* to head -- which is what the
 * device holds only when head is a descendant of it.
 */
data class Comparison(
    val status: String?,
    val files: List<CompareChange>,
)

/** One entry from a compare between two commits. */
data class CompareChange(
    val path: String,
    val status: ChangeStatus,
    val sha: String?,
    val previousPath: String? = null,
)

/**
 * Turns "what the device has" plus "what the remote says" into a [SyncPlan].
 *
 * Deliberately pure: no I/O, no clock, no Android. Everything that decides how
 * many bytes a sync costs lives here, so it can be tested exhaustively in
 * milliseconds.
 */
object SyncPlanner {
    /**
     * Whether a compare's file list describes the step from the device's
     * commit to head, and so can be applied to the manifest as a diff.
     *
     * Only when head descends from the base. After a force-push, a branch
     * reset or a branch switch the two have `diverged` (or head is `behind`),
     * and the three-dot compare lists changes from a merge base the device
     * never held: files the device has but head does not are simply not
     * mentioned, and nothing ever deletes them. A missing status is treated
     * the same way, because trusting it is the failure that cannot be seen.
     */
    fun compareIsUsable(status: String?): Boolean = status == "ahead" || status == "identical"

    /**
     * Full diff against a complete listing of the remote tree. Used for the
     * first sync, and as the fallback whenever an incremental compare cannot
     * be trusted -- a force-push, a rewritten history, or more changed files
     * than the compare endpoint will return.
     */
    fun fromTree(
        base: SyncBase,
        headCommit: String,
        remote: List<VaultEntry>,
    ): SyncPlan {
        val remoteByPath = remote.associateBy { it.path }
        val local = base.manifest

        val addedPaths = remoteByPath.keys - local.keys
        val removedPaths = local.keys - remoteByPath.keys

        var unchanged = 0
        val modifies = mutableListOf<VaultEntry>()
        for ((path, entry) in remoteByPath) {
            val localSha = local[path] ?: continue
            if (localSha == entry.sha) unchanged++ else modifies += entry
        }

        // A file that vanished from one path and appeared at another with the
        // same blob sha is a rename. Git does not record it as one, but pairing
        // them here means the content never crosses the network again.
        val removedBySha = removedPaths.groupBy { local.getValue(it) }.mapValues { it.value.toMutableList() }
        val renames = mutableListOf<Rename>()
        val adds = mutableListOf<VaultEntry>()
        for (path in addedPaths.sorted()) {
            val entry = remoteByPath.getValue(path)
            val candidates = removedBySha[entry.sha]
            val from = candidates?.removeFirstOrNull()
            if (from != null) renames += Rename(from = from, to = path, sha = entry.sha) else adds += entry
        }

        val renamedFrom = renames.mapTo(mutableSetOf()) { it.from }
        return SyncPlan(
            baseCommit = base.commit,
            headCommit = headCommit,
            adds = adds,
            modifies = modifies,
            renames = renames,
            deletes = (removedPaths - renamedFrom).sorted(),
            unchanged = unchanged,
        )
    }

    /**
     * Incremental diff from a compare between the device's commit and the new
     * head. Far cheaper than a tree listing, and the only place renames arrive
     * already identified.
     *
     * [lookup] supplies size and kind for a path, which compare does not carry;
     * entries it cannot resolve fall back to a zero size, which only affects
     * the progress estimate.
     */
    fun fromCompare(
        base: SyncBase,
        headCommit: String,
        changes: List<CompareChange>,
        filter: VaultFilter,
        lookup: (path: String) -> VaultEntry? = { null },
    ): SyncPlan {
        val adds = mutableListOf<VaultEntry>()
        val modifies = mutableListOf<VaultEntry>()
        val renames = mutableListOf<Rename>()
        val deletes = mutableListOf<String>()

        fun entryFor(
            path: String,
            sha: String?,
        ): VaultEntry? {
            val kind = filter.kindOf(path) ?: return null
            val resolved = lookup(path)
            return VaultEntry(
                path = path,
                sha = sha ?: resolved?.sha ?: return null,
                size = resolved?.size ?: 0L,
                kind = kind,
            )
        }

        for (change in changes) {
            val inVault = filter.accepts(change.path)
            val previous = change.previousPath
            when (change.status) {
                ChangeStatus.ADDED, ChangeStatus.COPIED ->
                    if (inVault) entryFor(change.path, change.sha)?.let(adds::add)

                ChangeStatus.MODIFIED, ChangeStatus.CHANGED ->
                    // Not if the device already holds exactly those bytes. The
                    // app's own commits come back through compare as changes to
                    // files it wrote itself, and fetching them again would
                    // overwrite the copy that is already correct.
                    if (inVault && change.sha != base.manifest[change.path]) {
                        entryFor(change.path, change.sha)?.let(modifies::add)
                    }

                ChangeStatus.REMOVED ->
                    if (change.path in base.manifest) deletes += change.path

                ChangeStatus.RENAMED -> {
                    // The old path leaves the vault regardless of whether the
                    // new one is still vault content.
                    val previousSha = previous?.let(base.manifest::get)
                    when {
                        !inVault -> if (previous != null && previous in base.manifest) deletes += previous
                        // Same bytes at a new name: move on disk, download nothing.
                        previous != null && previousSha != null && previousSha == change.sha ->
                            renames += Rename(from = previous, to = change.path, sha = previousSha)
                        else -> {
                            if (previous != null && previous in base.manifest) deletes += previous
                            entryFor(change.path, change.sha)?.let(adds::add)
                        }
                    }
                }

                ChangeStatus.UNCHANGED -> Unit
            }
        }

        return SyncPlan(
            baseCommit = base.commit,
            headCommit = headCommit,
            adds = adds,
            modifies = modifies,
            renames = renames,
            deletes = deletes.distinct(),
            unchanged = base.manifest.size - adds.size - modifies.size - renames.size - deletes.size,
        )
    }
}
