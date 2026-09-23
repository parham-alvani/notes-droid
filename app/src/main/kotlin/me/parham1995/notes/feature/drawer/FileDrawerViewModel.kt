package me.parham1995.notes.feature.drawer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.parham1995.notes.data.IconStore
import me.parham1995.notes.data.VaultIcons
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.feature.note.NoteTabs
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.ui.VaultRowItem
import javax.inject.Inject

/** One note, as the drawer lists it. */
data class DrawerNote(
    val id: Long,
    val title: String,
    /** The folder holding it, which is what tells two notes of a name apart. */
    val folder: String,
    val icon: IconSpec? = null,
)

/** One open tab. */
data class DrawerTab(
    val index: Int,
    val noteId: Long,
    val title: String,
    val active: Boolean,
)

data class FileDrawerUiState(
    val vaultLabel: String = "",
    val vaults: List<Pair<Long, String>> = emptyList(),
    val activeVaultId: Long = 0,
    val query: String = "",
    val matches: List<DrawerNote> = emptyList(),
    val tabs: List<DrawerTab> = emptyList(),
    val recent: List<DrawerNote> = emptyList(),
    /** The folder being shown, relative to the vault root. */
    val folder: String = "",
    val items: List<VaultRowItem> = emptyList(),
) {
    val filtering: Boolean get() = query.isNotBlank()

    /** The folder one level up, or null at the root. */
    val parent: String? get() = folder.takeIf { it.isNotEmpty() }?.substringBeforeLast('/', "")
}

/**
 * What the drawer needs: what is open, what was read lately, and what is
 * nearby.
 *
 * The browser answers "show me the vault". This answers "take me to another
 * file without losing the one I am reading", which is a different question:
 * you are somewhere, and the useful destinations are the other tabs, the last
 * few notes, and the folder this note sits in. Hence a folder walked one level
 * at a time rather than an expandable tree -- a drawer is 320dp wide, and a
 * tree of 531 folders at depth seven is not something to put in it.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class FileDrawerViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val tabs: NoteTabs,
        icons: IconStore,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val _state = MutableStateFlow(FileDrawerUiState())
        val state: StateFlow<FileDrawerUiState> = _state.asStateFlow()

        private val queries = MutableStateFlow("")

        /** In saved state, so the drawer reopens where it was after process death. */
        private val folder: StateFlow<String> = savedState.getStateFlow(KEY_FOLDER, "")

        /**
         * Held rather than collected per row. The assignments change only when
         * the vault does, and every list here would otherwise wait on a flow to
         * draw an icon.
         */
        private var iconConfig: VaultIcons = VaultIcons.EMPTY
        private var activeVault: Long = 0

        init {
            viewModelScope.launch { icons.config.collect { iconConfig = it } }

            viewModelScope.launch {
                combine(repository.vaults(), repository.activeVaultId) { all, id -> all to id }
                    .collect { (all, id) ->
                        activeVault = id
                        _state.update { current ->
                            current.copy(
                                vaults = all.map { it.id to it.label },
                                activeVaultId = id,
                                vaultLabel = all.firstOrNull { it.id == id }?.label.orEmpty(),
                            )
                        }
                    }
            }

            viewModelScope.launch {
                repository.recentlyOpened(RECENT).collect { notes ->
                    val rows = notes.map { it.asDrawerNote() }
                    _state.update { it.copy(recent = rows) }
                }
            }

            viewModelScope.launch {
                tabs.state.collect { open ->
                    val rows =
                        open.tabs.mapIndexed { index, tab ->
                            DrawerTab(index, tab.noteId, tab.title, active = index == open.active)
                        }
                    _state.update { it.copy(tabs = rows) }
                }
            }

            viewModelScope.launch {
                folder
                    .flatMapLatest { at -> repository.childrenFlow(at).map { at to it } }
                    .collect { (at, children) ->
                        val rows =
                            children.map { item ->
                                VaultRowItem(item, iconConfig.forPath(activeVault, item.path, item.isFolder))
                            }
                        _state.update { it.copy(folder = at, items = rows) }
                    }
            }

            viewModelScope.launch {
                queries.debounce(DEBOUNCE_MS).distinctUntilChanged().collect { query ->
                    val found =
                        if (query.isBlank()) {
                            emptyList()
                        } else {
                            repository.quickSwitch(query).map { it.asDrawerNote() }
                        }
                    _state.update { it.copy(matches = found) }
                }
            }
        }

        fun setQuery(text: String) {
            _state.update { it.copy(query = text) }
            queries.value = text
        }

        /**
         * Puts the drawer on the folder holding [noteId].
         *
         * Called every time it opens rather than once: the note being read
         * changes underneath it, and a drawer still showing where you were
         * three notes ago is one you stop opening.
         */
        fun locate(noteId: Long) =
            viewModelScope.launch {
                setQuery("")
                savedState[KEY_FOLDER] =
                    repository
                        .locate(noteId)
                        ?.second
                        ?.substringBeforeLast('/', "")
                        .orEmpty()
            }

        fun openFolder(path: String) {
            savedState[KEY_FOLDER] = path
        }

        fun switchVault(id: Long) =
            viewModelScope.launch {
                repository.setActiveVault(id)
                openFolder("")
                setQuery("")
            }

        fun selectTab(index: Int) = tabs.select(index)

        fun closeTab(index: Int) = tabs.close(index)

        private fun NoteEntity.asDrawerNote() =
            DrawerNote(
                id = id,
                title = title,
                folder = parent,
                icon = iconConfig.forFile(vaultId, path),
            )

        private companion object {
            const val RECENT = 8
            const val DEBOUNCE_MS = 150L
            const val KEY_FOLDER = "drawer_folder"
        }
    }
