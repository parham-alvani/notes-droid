package me.parham1995.notes.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Syncs over the GitHub REST API.
 *
 * The shape that matters is the cheap path: ask whether the branch moved, with
 * the ETag from last time. If it did not, that one request is the whole sync
 * and GitHub does not even bill it. When it did move, a single compare says
 * exactly what changed -- including renames, which are applied on disk rather
 * than re-downloaded.
 *
 * A full tree listing is only needed for the first sync, or when the compare
 * cannot be trusted.
 */
class RestVaultSync(
    private val client: GitHubClient,
    private val branch: String,
    private val filter: VaultFilter = VaultFilter(),
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    /** Narrates the decisions a sync makes, for the on-device journal. */
    private val log: suspend (String) -> Unit = {},
) : VaultSync {
    override suspend fun plan(base: SyncBase): SyncPlan {
        val head =
            when (val response = client.head(branch, base.etagRef)) {
                is Conditional.NotModified -> {
                    // Nothing moved. An empty plan at the same commit says so
                    // without inventing an exception for the common case.
                    log("head unchanged (304) - one request, not billed")
                    return SyncPlan(base.commit, base.commit.orEmpty(), etagRef = base.etagRef)
                }

                is Conditional.Fresh -> response
            }

        if (base.commit == head.value) {
            return SyncPlan(base.commit, head.value, etagRef = head.etag)
        }

        incrementalPlan(base, head.value)?.let { return it.copy(etagRef = head.etag) }

        val remote = client.tree(head.value, filter)
        log("tree at ${head.value.take(7)}: ${remote.size} vault files")
        return SyncPlanner.fromTree(base, head.value, remote).copy(etagRef = head.etag)
    }

    /**
     * Returns null when compare cannot be used and the caller must fall back to
     * a full tree: no local commit yet, the history was rewritten so the base is
     * unreachable, or more files changed than compare will report.
     */
    private suspend fun incrementalPlan(
        base: SyncBase,
        head: String,
    ): SyncPlan? {
        val from = base.commit ?: return null
        val changes =
            try {
                client.compare(from, head)
            } catch (_: GitHubException.NotFound) {
                // The base commit is gone -- force-push or a rewritten branch.
                log("base commit unreachable - falling back to a full tree listing")
                return null
            }
        // At the cap the response is truncated and would silently miss files.
        if (changes.size >= COMPARE_FILE_LIMIT) {
            log("compare hit its ${COMPARE_FILE_LIMIT}-file cap - falling back to a full tree listing")
            return null
        }
        log("compare returned ${changes.size} changed paths")

        val sizes = base.manifest
        return SyncPlanner.fromCompare(base, head, changes, filter) { path ->
            sizes[path]?.let { VaultEntry(path, it, 0L, filter.kindOf(path) ?: BlobKind.OTHER) }
        }
    }

    override suspend fun apply(
        plan: SyncPlan,
        sink: VaultSink,
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val total = plan.renames.size + plan.deletes.size + plan.downloads.size
        val done = AtomicInteger(0)

        fun step() = onProgress(done.incrementAndGet(), total)

        // Renames first: they only move bytes already on disk, and the planner
        // guarantees no rename source is also a delete.
        for (rename in plan.renames) {
            sink.move(rename.from, rename.to)
            step()
        }

        for (path in plan.deletes) {
            sink.delete(path)
            step()
        }

        // Downloads run in parallel but stay well under the secondary rate
        // limit; RateLimiter paces the requests themselves.
        val gate = Semaphore(concurrency)
        coroutineScope {
            plan.downloads
                .map { entry ->
                    async {
                        gate.withPermit {
                            // Images are known but deliberately not fetched;
                            // they arrive when a note that embeds one is opened.
                            if (entry.kind == BlobKind.IMAGE) {
                                sink.record(entry, LocalState.ABSENT)
                            } else {
                                sink.write(entry.path, client.blob(entry.sha), entry.sha)
                            }
                            step()
                        }
                    }
                }.awaitAll()
        }
    }

    private companion object {
        const val DEFAULT_CONCURRENCY = 6

        /** GitHub caps the compare endpoint's `files` array at 300. */
        const val COMPARE_FILE_LIMIT = 300
    }
}
