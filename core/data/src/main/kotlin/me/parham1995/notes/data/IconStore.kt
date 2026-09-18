package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.parham1995.notes.data.database.SyncStateDao
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
        syncState: SyncStateDao,
        private val log: SyncLog,
    ) {
        private val mutex = Mutex()
        private var cached: Pair<Long?, IconicConfig>? = null

        val config: Flow<IconicConfig> =
            syncState
                .observe()
                .map { it?.lastSyncAt }
                .distinctUntilChanged()
                .map { syncedAt -> configAt(syncedAt) }
                .flowOn(Dispatchers.IO)

        private suspend fun configAt(syncedAt: Long?): IconicConfig =
            mutex.withLock {
                cached?.takeIf { it.first == syncedAt }?.second ?: load().also { cached = syncedAt to it }
            }

        private suspend fun load(): IconicConfig {
            val text = files.readText(VaultFilter.ICONIC_CONFIG) ?: return IconicConfig.EMPTY
            return runCatching { IconicConfig.parse(text) }
                .onFailure {
                    // A plugin upgrade that changes the file's shape should cost
                    // the icons, not the app.
                    log.warn("could not read icon assignments: ${it.message}")
                }.getOrDefault(IconicConfig.EMPTY)
        }
    }
