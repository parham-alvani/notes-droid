package me.parham1995.notes.feature.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.BrowserSort
import me.parham1995.notes.data.CrashLog
import me.parham1995.notes.data.IconStore
import me.parham1995.notes.data.ObsidianConfigStore
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.SyncWorker
import me.parham1995.notes.data.VaultBookmarks
import me.parham1995.notes.data.VaultFileSource
import me.parham1995.notes.data.VaultItem
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.ui.VaultRowItem
import me.parham1995.notes.ui.folderListing
import java.io.File
import javax.inject.Inject

/** A recently-opened note, likewise. */
data class RecentRow(
    val note: NoteEntity,
    val icon: IconSpec? = null,
)

data class BrowserUiState(
    val path: String = "",
    val items: List<VaultRowItem> = emptyList(),
    val recent: List<RecentRow> = emptyList(),
    /** The previous run ended in a crash and nobody has been told. */
    val crashed: Boolean = false,
    val noteCount: Int = 0,
    /**
     * The repositories, when there is more than one. Empty otherwise, which is
     * what keeps the switcher off screen for a single-repository vault.
     */
    val vaults: List<VaultEntity> = emptyList(),
    val activeVaultId: Long = 0,
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val syncProgress: Pair<Int, Int>? = null,
) {
    /** Path segments, for the breadcrumb. Depth reaches seven in a real vault. */
    val crumbs: List<Pair<String, String>>
        get() {
            if (path.isEmpty()) return emptyList()
            val parts = path.split('/')
            return parts.indices.map { index ->
                parts[index] to parts.take(index + 1).joinToString("/")
            }
        }
}

@HiltViewModel
class BrowserViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val crashLog: CrashLog,
        private val files: VaultFileSource,
        private val icons: IconStore,
        private val scheduler: SyncScheduler,
        private val settings: SettingsStore,
        configs: ObsidianConfigStore,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /**
         * The vault's own bookmarks, from Obsidian's Bookmarks plugin.
         *
         * Apart from [state] because nothing else on the screen depends on
         * them, and folding a sixth input into its `combine` would buy nothing.
         */
        val bookmarks: StateFlow<VaultBookmarks> =
            configs
                .bookmarks(repository.activeVaultId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), VaultBookmarks.EMPTY)

        /**
         * The folder being shown. Kept in saved state so a process killed in
         * the background comes back to the folder it was in, not the root.
         */
        private val path: StateFlow<String> = savedState.getStateFlow(KEY_FOLDER, "")

        // Follows the database rather than sampling it once. The previous
        // version queried in init, which before the first sync is an empty
        // vault, and nothing asked again -- so the tree stayed empty while
        // search, which queries per keystroke, worked fine.
        private val rows: Flow<List<VaultRowItem>> =
            repository
                .folderListing(
                    folder = path,
                    icons = icons.config,
                    order = settings.settings.map { it.reading.browserSort.comparator() },
                ).map { it.rows }

        /**
         * Folders stay above files whatever the order, because a folder is
         * somewhere to go rather than something to read, and mixing the two by
         * date makes the tree unusable as a tree.
         */
        private fun BrowserSort.comparator(): Comparator<VaultItem> =
            compareByDescending<VaultItem> { it.isFolder }
                .thenBy {
                    when (this) {
                        BrowserSort.NAME -> 0L
                        BrowserSort.RECENTLY_OPENED -> -(it.openedAt ?: 0L)
                        BrowserSort.RECENTLY_CHANGED -> -it.changedAt
                    }
                }.thenBy { it.name.lowercase() }

        private val vaults: Flow<List<VaultEntity>> =
            repository.vaults().map { found -> if (found.size > 1) found else emptyList() }

        /** Switching vault also returns to that vault's root. */
        fun switchVault(id: Long) =
            viewModelScope.launch {
                repository.setActiveVault(id)
                open("")
            }

        /**
         * The few notes worth jumping straight back to.
         *
         * Three, not twelve. Twelve plus its heading and the "All notes"
         * heading below it filled the whole first screen, so opening the app
         * to browse always began with a scroll past what was read yesterday.
         */
        private val recent: Flow<List<RecentRow>> =
            combine(repository.recentlyOpened(limit = RECENT_ON_ROOT), icons.config) { notes, config ->
                notes.map { RecentRow(it, config.forFile(it.vaultId, it.path)) }
            }

        /**
         * Whether the app died last time it ran.
         *
         * Read once at startup rather than observed: what matters is that the
         * previous run ended badly, and that does not change while this one is
         * open. The card in Settings holds the trace; this only says to go and
         * look, because for three days it crashed on launch and nothing
         * anywhere said so.
         *
         * An input to the state like everything else, not a value sampled
         * while building it: sampled, the banner appeared only if the database
         * happened to change after the read, and Dismiss did nothing until it
         * changed again.
         */
        private val crashed = MutableStateFlow(false)

        fun dismissCrashNotice() {
            crashed.value = false
        }

        private val _state = MutableStateFlow(BrowserUiState())
        val state: StateFlow<BrowserUiState> = _state.asStateFlow()

        init {
            viewModelScope.launch { crashed.value = crashLog.read() != null }
            viewModelScope.launch {
                browserStates(
                    path = path,
                    rows = rows,
                    recent = recent,
                    noteCount = repository.noteCount,
                    vaults = combine(vaults, repository.activeVaultId) { all, active -> all to active },
                    crashed = crashed,
                ).collect { next ->
                    _state.value = next.copy(syncing = _state.value.syncing, syncProgress = _state.value.syncProgress)
                }
            }

            viewModelScope.launch {
                scheduler.observe().collect { infos ->
                    // Only a RUNNING job counts as syncing. Treating anything
                    // unfinished as active meant a job backing off between
                    // retries -- or one orphaned by a force-stop -- showed a
                    // spinner indefinitely.
                    val active = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    _state.value =
                        _state.value.copy(
                            syncing = active != null,
                            syncProgress =
                                active?.progress?.let { data ->
                                    val total = data.getInt(SyncWorker.KEY_TOTAL, 0)
                                    if (total > 0) data.getInt(SyncWorker.KEY_DONE, 0) to total else null
                                },
                        )
                }
            }
        }

        /**
         * The file on disk, fetching it first if this install has only ever
         * recorded it. Null when it cannot be had at all.
         */
        suspend fun attachment(path: String): File? = files.localFile(repository.activeVaultId.first(), path)

        fun open(next: String) {
            savedState[KEY_FOLDER] = next
        }

        /**
         * Opens [initial], once for the life of this screen.
         *
         * Once, because the screen asks again every time it is composed --
         * coming back from a note, or after the process was killed -- and each
         * time it would have thrown away the folder the person had moved to.
         */
        fun start(initial: String) {
            if (savedState.get<Boolean>(KEY_STARTED) == true) return
            savedState[KEY_STARTED] = true
            if (initial.isNotEmpty()) open(initial)
        }

        /** Up one level; returns false at the root so the caller can exit. */
        fun up(): Boolean {
            val current = path.value
            if (current.isEmpty()) return false
            open(current.substringBeforeLast('/', ""))
            return true
        }

        /** Somewhere in the vault you have not been for a while. */
        fun randomNote(onOpen: (Long) -> Unit) =
            viewModelScope.launch {
                repository.randomNote()?.let { onOpen(it.id) }
            }

        fun refresh() =
            viewModelScope.launch {
                scheduler.syncNow(settings.current().syncOnWifiOnly)
            }
    }

/**
 * The browser's state from its inputs.
 *
 * Nested because `combine` is typed up to five flows and the vararg form loses
 * every type in the lambda.
 */
internal fun browserStates(
    path: Flow<String>,
    rows: Flow<List<VaultRowItem>>,
    recent: Flow<List<RecentRow>>,
    noteCount: Flow<Int>,
    vaults: Flow<Pair<List<VaultEntity>, Long>>,
    crashed: Flow<Boolean>,
): Flow<BrowserUiState> =
    combine(
        path,
        rows,
        recent,
        noteCount,
        combine(vaults, crashed) { pair, died -> pair to died },
    ) { currentPath, currentItems, recentRows, count, (repositories, died) ->
        BrowserUiState(
            path = currentPath,
            items = currentItems,
            recent = recentRows,
            noteCount = count,
            vaults = repositories.first,
            activeVaultId = repositories.second,
            loading = false,
            crashed = died,
        )
    }

private const val KEY_FOLDER = "browser_folder"
private const val KEY_STARTED = "browser_started"

/** Kept short on purpose; see [BrowserViewModel.recent]. */
private const val RECENT_ON_ROOT = 3

private const val STOP_AFTER_MS = 5_000L
