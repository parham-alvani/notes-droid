package me.parham1995.notes.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.parham1995.notes.data.SearchHit
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.NoteEntity
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val quick: List<NoteEntity> = emptyList(),
    val hits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(SearchUiState())
        val state: StateFlow<SearchUiState> = _state.asStateFlow()

        private val queries = MutableStateFlow("")

        init {
            viewModelScope.launch {
                queries
                    .debounce(DEBOUNCE_MS)
                    .distinctUntilChanged()
                    .collect { query ->
                        if (query.isBlank()) {
                            _state.value = _state.value.copy(quick = emptyList(), hits = emptyList(), searching = false)
                            return@collect
                        }
                        _state.value = _state.value.copy(searching = true)
                        // The quick switcher is what answers most searches, so
                        // it runs first and the full-text pass fills in under it.
                        val quick = repository.quickSwitch(query)
                        _state.value = _state.value.copy(quick = quick)
                        val hits = repository.search(query)
                        _state.value = _state.value.copy(hits = hits, searching = false)
                    }
            }
        }

        fun onQueryChange(query: String) {
            _state.value = _state.value.copy(query = query)
            queries.value = query
        }

        fun clear() = onQueryChange("")

        private companion object {
            const val DEBOUNCE_MS = 200L
        }
    }
