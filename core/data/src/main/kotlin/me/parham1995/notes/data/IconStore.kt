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
         * Each vault carries its own plugin configuration, written in its own
         * paths, so the vault has to be named to get an answer -- a rule like
         * `^Companies/[^/]*$` belongs to one vault and means nothing in another.
         */
        private suspend fun load(): VaultIcons {
            val byVault =
                vaults.all().mapNotNull { vault ->
                    val text = files.readText(vault.id, VaultFilter.ICONIC_CONFIG) ?: return@mapNotNull null
                    val parsed =
                        runCatching { IconicConfig.parse(text) }
                            .onFailure {
                                // A plugin upgrade that changes the file's shape
                                // should cost the icons, not the app.
                                log.warn("could not read icon assignments for ${vault.label}: ${it.message}")
                            }.getOrNull() ?: return@mapNotNull null
                    vault.id to parsed
                }
            return VaultIcons(byVault)
        }
    }

/**
 * Each vault's icon assignments, kept apart.
 *
 * Every vault carries its own plugin configuration written in its own paths, so
 * the vault has to be named to get an answer. With one vault this is exactly
 * the one config it always was.
 */
class VaultIcons(
    private val byVault: List<Pair<Long, IconicConfig>>,
) {
    val isEmpty: Boolean get() = byVault.all { it.second.isEmpty }

    fun forPath(
        vaultId: Long,
        path: String,
        isFolder: Boolean,
    ): IconSpec? = byVault.firstOrNull { it.first == vaultId }?.second?.forPath(path, isFolder)

    fun forFile(
        vaultId: Long,
        path: String,
    ): IconSpec? = forPath(vaultId, path, isFolder = false)

    companion object {
        val EMPTY = VaultIcons(emptyList())
    }
}
