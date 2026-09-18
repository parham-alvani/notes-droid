package me.parham1995.notes.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.parham1995.notes.data.IconStore
import me.parham1995.notes.data.RenderedNote
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.BacklinkRow
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.ui.VaultRowItem
import javax.inject.Inject

data class NoteUiState(
    val loading: Boolean = true,
    val note: RenderedNote? = null,
    val backlinks: List<BacklinkRow> = emptyList(),
    val icon: IconSpec? = null,
    /**
     * What the folder holds, when this note is a folder's landing page. Empty
     * for an ordinary note, which is what hides the tabs.
     */
    val contents: List<VaultRowItem> = emptyList(),
    val missing: Boolean = false,
) {
    val isFolderNote: Boolean get() = note?.isFolderNote == true

    /** Targets with no destination, so the renderer can style them as broken. */
    val brokenLinks: Set<String> get() = note?.brokenTargets.orEmpty()
}

@HiltViewModel
class NoteViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val icons: IconStore,
    ) : ViewModel() {
        private val _state = MutableStateFlow(NoteUiState())
        val state: StateFlow<NoteUiState> = _state.asStateFlow()

        fun load(id: Long) {
            if (_state.value.note?.id == id) return
            _state.value = NoteUiState(loading = true)
            viewModelScope.launch {
                val note = repository.note(id)
                if (note == null) {
                    _state.value = NoteUiState(loading = false, missing = true)
                    return@launch
                }
                repository.markOpened(id)
                val assignments = icons.config.first()
                _state.value =
                    NoteUiState(
                        loading = false,
                        note = note,
                        backlinks = repository.backlinks(id),
                        icon = assignments.forFile(note.path),
                        contents =
                            if (!note.isFolderNote) {
                                emptyList()
                            } else {
                                // `A/B/B.md` is the landing page for `A/B`.
                                // `children` already leaves the note itself
                                // out of its own folder's listing.
                                repository
                                    .children(note.path.substringBeforeLast('/', ""))
                                    .map { VaultRowItem(it, assignments.forPath(it.path, it.isFolder)) }
                            },
                    )
            }
        }

        /** The note a wikilink points at, or null when it is broken. */
        fun targetOf(target: String): Long? =
            _state.value.note
                ?.linkTargets
                ?.get(target)
    }
