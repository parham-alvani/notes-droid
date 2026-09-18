package me.parham1995.notes.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.parham1995.notes.data.IconStore
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.SyncScheduler
import me.parham1995.notes.data.SyncWorker
import me.parham1995.notes.data.VaultFileSource
import me.parham1995.notes.data.VaultItem
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.ui.VaultRowItem
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
    val noteCount: Int = 0,
    /**
     * The repositories, when there is more than one. Empty otherwise, which is
     * what keeps the switcher off screen for a single-repository vault.
     */
    val vaults: List<VaultEntity> = emptyList(),
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowserViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val files: VaultFileSource,
        private val icons: IconStore,
        private val scheduler: SyncScheduler,
        private val settings: SettingsStore,
    ) : ViewModel() {
        private val path = MutableStateFlow("")

        // Follows the database rather than sampling it once. The previous
        // version queried in init, which before the first sync is an empty
        // vault, and nothing asked again -- so the tree stayed empty while
        // search, which queries per keystroke, worked fine.
        private val items: Flow<List<VaultItem>> =
            path.flatMapLatest { current -> repository.childrenFlow(current) }

        // Resolving here rather than in the row composable keeps the rule
        // regexes off the composition: a rule is tested against every visible
        // item, and the browser recomposes on every scroll.
        private val rows: Flow<List<VaultRowItem>> =
            combine(items, icons.config) { current, config ->
                current.map { VaultRowItem(it, config.forPath(it.path, it.isFolder)) }
            }

        private val vaults: Flow<List<VaultEntity>> =
            repository.vaults().map { found -> if (found.size > 1) found else emptyList() }

        private val recent: Flow<List<RecentRow>> =
            combine(repository.recentlyOpened(), icons.config) { notes, config ->
                notes.map { RecentRow(it, config.forFile(it.path)) }
            }

        private val _state = MutableStateFlow(BrowserUiState())
        val state: StateFlow<BrowserUiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                combine(
                    path,
                    rows,
                    recent,
                    repository.noteCount,
                    vaults,
                ) { currentPath, currentItems, recentRows, count, repositories ->
                    BrowserUiState(
                        path = currentPath,
                        items = currentItems,
                        recent = recentRows,
                        noteCount = count,
                        vaults = repositories,
                        loading = false,
                    )
                }.collect { next ->
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
        suspend fun attachment(path: String): File? = files.localFile(path)

        fun open(next: String) {
            path.value = next
        }

        /** Up one level; returns false at the root so the caller can exit. */
        fun up(): Boolean {
            val current = path.value
            if (current.isEmpty()) return false
            open(current.substringBeforeLast('/', ""))
            return true
        }

        fun refresh() =
            viewModelScope.launch {
                scheduler.syncNow(settings.current().syncOnWifiOnly)
            }
    }
