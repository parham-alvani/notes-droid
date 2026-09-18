package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.parham1995.notes.data.database.SyncStateDao
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.icons.IconicConfig
import me.parham1995.notes.sync.VaultFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vault's icon assignments, kept in step with what has been synced.
 *
 * The assignments live in a file inside the vault, so the moment to re-read
 * them is after a sync -- which `sync_state` already records. Hanging the
 * reload off that means there is no separate invalidation to get wrong.
 *
 * It keys on when the sync finished rather than on the head commit, which
 * would be the tighter signal but not a sufficient one: the first sync after
 * this file became vault content fetches it *without* the commit moving, so a
 * commit-keyed reload would leave the icons missing until the vault next
 * changed. Re-reading a few tens of kilobytes per sync is not worth being
 * clever about.
 */
@Singleton
class IconStore
    @Inject
    constructor(
        private val files: VaultFileStore,
        private val vaults: VaultDao,
        syncState: SyncStateDao,
        private val log: SyncLog,
    ) {
        private val mutex = Mutex()
        private var cached: Pair<Long?, VaultIcons>? = null

        val config: Flow<VaultIcons> =
            syncState
                .observe()
                .map { it?.lastSyncAt }
                .distinctUntilChanged()
                .map { syncedAt -> configAt(syncedAt) }
                .flowOn(Dispatchers.IO)

        private suspend fun configAt(syncedAt: Long?): VaultIcons =
            mutex.withLock {
                cached?.takeIf { it.first == syncedAt }?.second ?: load().also { cached = syncedAt to it }
            }

        /**
         * One set of assignments per repository.
         *
         * Each repository carries its own plugin configuration, written in
         * paths relative to itself -- a rule like `^Companies/[^/]*$` means
         * nothing once the repository is mounted under a folder. Rather than
         * rewriting the rules, the mount is stripped off the path before the
         * repository's own config is asked about it.
         */
        private suspend fun load(): VaultIcons {
            val mounted =
                vaults.all().mapNotNull { vault ->
                    val path = vault.mounted(VaultFilter.ICONIC_CONFIG)
                    val text = files.readText(path) ?: return@mapNotNull null
                    val parsed =
                        runCatching { IconicConfig.parse(text) }
                            .onFailure {
                                // A plugin upgrade that changes the file's shape
                                // should cost the icons, not the app.
                                log.warn("could not read icon assignments for ${vault.label}: ${it.message}")
                            }.getOrNull() ?: return@mapNotNull null
                    vault.mount to parsed
                }
            return VaultIcons(mounted)
        }
    }

/**
 * The icon assignments of every repository, addressed by the paths the app
 * actually uses.
 *
 * The longest matching mount wins, so a repository mounted at `work` answers
 * for `work/...` and the root-mounted one answers for everything else. With a
 * single repository this is exactly the one config it always was.
 */
class VaultIcons(
    private val byMount: List<Pair<String, IconicConfig>>,
) {
    val isEmpty: Boolean get() = byMount.all { it.second.isEmpty }

    fun forPath(
        path: String,
        isFolder: Boolean,
    ): IconSpec? {
        val (mount, config) =
            byMount
                .filter { (mount, _) -> mount.isEmpty() || path == mount || path.startsWith("$mount/") }
                .maxByOrNull { (mount, _) -> mount.length }
                ?: return null
        val relative = if (mount.isEmpty()) path else path.removePrefix("$mount/")
        return config.forPath(relative, isFolder)
    }

    fun forFile(path: String): IconSpec? = forPath(path, isFolder = false)

    companion object {
        val EMPTY = VaultIcons(emptyList())
    }
}
