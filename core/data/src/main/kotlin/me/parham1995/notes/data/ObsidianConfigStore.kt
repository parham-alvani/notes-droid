package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.SyncStateDao
import me.parham1995.notes.obsidian.Bookmark
import me.parham1995.notes.obsidian.Bookmarks
import me.parham1995.notes.obsidian.DailyNotes
import me.parham1995.notes.sync.VaultFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One vault's bookmarks, with the notes they name already looked up.
 *
 * [noteIds] is keyed by the bookmarked path. A path missing from it is a
 * bookmark to a note this vault does not have -- renamed on the desktop since,
 * or never synced -- and is shown as one rather than hidden.
 */
data class VaultBookmarks(
    val vaultId: Long = 0,
    val items: List<Bookmark> = emptyList(),
    val noteIds: Map<String, Long> = emptyMap(),
) {
    companion object {
        val EMPTY = VaultBookmarks()
    }
}

/**
 * Obsidian's own settings that say how a vault is meant to be got around:
 * the Bookmarks plugin's list and where the Daily notes plugin keeps a day.
 *
 * Both files are synced as `CONFIG` blobs and read from disk on demand -- they
 * are a few kilobytes, and parsing on read means there is no second copy in
 * the database to keep in step. Like [IconStore], a read is re-done after
 * every sync, which is when the files can have changed.
 *
 * Each vault carries its own `.obsidian`, so every call names the vault.
 */
@Singleton
class ObsidianConfigStore
    @Inject
    constructor(
        private val files: VaultFileStore,
        private val notes: NoteDao,
        syncState: SyncStateDao,
        private val log: SyncLog,
    ) {
        private val synced: Flow<Long?> = syncState.observe().map { it?.lastSyncAt }.distinctUntilChanged()

        /** The bookmarks of whichever vault [vault] names, kept current across syncs. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun bookmarks(vault: Flow<Long>): Flow<VaultBookmarks> =
            combine(vault, synced) { id, _ -> id }
                .mapLatest { bookmarks(it) }
                .flowOn(Dispatchers.IO)

        suspend fun bookmarks(vaultId: Long): VaultBookmarks {
            val items = read(vaultId, VaultFilter.BOOKMARKS, "bookmarks", Bookmarks::parse).orEmpty()
            val ids =
                files(items).mapNotNull { path -> notes.idOf(vaultId, path)?.let { path to it } }.toMap()
            return VaultBookmarks(vaultId, items, ids)
        }

        /** The daily notes settings of whichever vault [vault] names, kept current across syncs. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun dailyNotes(vault: Flow<Long>): Flow<DailyNotes> =
            combine(vault, synced) { id, _ -> id }
                .mapLatest { dailyNotes(it) }
                .flowOn(Dispatchers.IO)

        /** Where [vaultId] keeps its daily notes; the plugin's defaults when it has not said. */
        suspend fun dailyNotes(vaultId: Long): DailyNotes =
            read(vaultId, VaultFilter.DAILY_NOTES, "daily notes settings", DailyNotes::parse) ?: DailyNotes()

        private suspend fun <T> read(
            vaultId: Long,
            path: String,
            what: String,
            parse: (String) -> T,
        ): T? {
            val text = files.readText(vaultId, path) ?: return null
            // A file Obsidian changes the shape of should cost the feature,
            // not the app.
            return runCatching { parse(text) }
                .onFailure { log.warn("could not read the $what of vault $vaultId: ${it.message}") }
                .getOrNull()
        }

        private fun files(items: List<Bookmark>): List<String> =
            items.flatMap {
                when (it) {
                    is Bookmark.File -> listOf(it.path)
                    is Bookmark.Group -> files(it.items)
                    else -> emptyList()
                }
            }
    }
