package me.parham1995.notes.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.parham1995.notes.data.RenderedNote
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.BacklinkRow
import javax.inject.Inject

data class NoteUiState(
    val loading: Boolean = true,
    val note: RenderedNote? = null,
    val backlinks: List<BacklinkRow> = emptyList(),
    val missing: Boolean = false,
) {
    /** Targets with no destination, so the renderer can style them as broken. */
    val brokenLinks: Set<String> get() = note?.brokenTargets.orEmpty()
}

@HiltViewModel
class NoteViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
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
                _state.value =
                    NoteUiState(
                        loading = false,
                        note = note,
                        backlinks = repository.backlinks(id),
                    )
            }
        }

        /** The note a wikilink points at, or null when it is broken. */
        fun targetOf(target: String): Long? =
            _state.value.note
                ?.linkTargets
                ?.get(target)
    }
