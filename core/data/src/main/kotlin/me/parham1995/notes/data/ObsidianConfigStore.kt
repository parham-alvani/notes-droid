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
import me.parham1995.notes.markdown.TaskStatuses
import me.parham1995.notes.obsidian.Beautitab
import me.parham1995.notes.obsidian.Bookmark
import me.parham1995.notes.obsidian.Bookmarks
import me.parham1995.notes.obsidian.DailyNotes
import me.parham1995.notes.obsidian.TasksPlugin
import me.parham1995.notes.obsidian.Templates
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
 * What each task status means in one vault, as of now.
 *
 * An interface for the indexer's sake, as [HiddenRules] is: its tests have no
 * `.obsidian` to read the statuses from.
 */
fun interface TaskStatusRules {
    suspend fun forVault(vaultId: Long): TaskStatuses

    companion object {
        /** The four every vault has, for a vault that says nothing of its own. */
        val DEFAULT = TaskStatusRules { TaskStatuses.DEFAULT }
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
    ) : TaskStatusRules {
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

        /** The Beautitab plugin's settings of whichever vault [vault] names, kept current across syncs. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun beautitab(vault: Flow<Long>): Flow<Beautitab> =
            combine(vault, synced) { id, _ -> id }
                .mapLatest { beautitab(it) }
                .flowOn(Dispatchers.IO)

        /** What the home page shows for [vaultId]; the plugin's defaults when it has no settings. */
        suspend fun beautitab(vaultId: Long): Beautitab =
            read(vaultId, VaultFilter.BEAUTITAB, "Beautitab settings", Beautitab::parse) ?: Beautitab()

        /** The Templates plugin's settings for [vaultId]; its defaults, with no folder, when it has none. */
        suspend fun templates(vaultId: Long): Templates =
            read(vaultId, VaultFilter.TEMPLATES, "templates settings", Templates::parse) ?: Templates()

        /** The task statuses of whichever vault [vault] names, kept current across syncs. */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun taskStatuses(vault: Flow<Long>): Flow<TaskStatuses> =
            combine(vault, synced) { id, _ -> id }
                .mapLatest { forVault(it) }
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)

        /**
         * The Tasks plugin's statuses for [vaultId]: the four defaults when it
         * has no settings, or settings that cannot be read.
         */
        override suspend fun forVault(vaultId: Long): TaskStatuses =
            read(vaultId, VaultFilter.TASKS, "Tasks plugin settings", TasksPlugin::statuses) ?: TaskStatuses.DEFAULT

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
