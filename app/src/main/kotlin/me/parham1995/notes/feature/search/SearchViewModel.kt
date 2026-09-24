package me.parham1995.notes.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.parham1995.notes.data.FtsQuery
import me.parham1995.notes.data.IconStore
import me.parham1995.notes.data.SearchHit
import me.parham1995.notes.data.VaultIcons
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.icons.IconSpec
import javax.inject.Inject

/** A quick-switcher row, with the icon Iconic gives the note. */
data class QuickRow(
    val note: NoteEntity,
    val icon: IconSpec? = null,
)

/** A full-text result, likewise. */
data class HitRow(
    val hit: SearchHit,
    val icon: IconSpec? = null,
)

data class SearchUiState(
    val query: String = "",
    val quick: List<QuickRow> = emptyList(),
    val hits: List<HitRow> = emptyList(),
    val searching: Boolean = false,
)

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
        icons: IconStore,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /**
         * What was typed, in saved state so a search survives the process
         * being killed while the app was in the background.
         */
        private val queries: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")

        private val _state = MutableStateFlow(SearchUiState(query = queries.value))
        val state: StateFlow<SearchUiState> = _state.asStateFlow()

        /**
         * Held rather than collected per query. Search runs on every keystroke
         * and the assignments only change when the vault does, so waiting on
         * the flow inside the debounce would put a file read on the typing path.
         */
        private val iconConfig: StateFlow<VaultIcons> =
            icons.config.stateIn(viewModelScope, SharingStarted.Eagerly, VaultIcons.EMPTY)

        init {
            viewModelScope.launch {
                queries
                    .searchSteps(
                        vault = repository.activeVaultId,
                        debounceMs = DEBOUNCE_MS,
                        quick = { query, _ ->
                            // `path:Garden -red` is not anybody's note name,
                            // and matching it as one offers nonsense above
                            // the results the operators were typed for.
                            if (FtsQuery.usesOperators(query)) {
                                emptyList()
                            } else {
                                repository
                                    .quickSwitch(query)
                                    .map { QuickRow(it, iconConfig.value.forFile(it.vaultId, it.path)) }
                            }
                        },
                        full = { query, vaultId ->
                            repository.search(query).map { HitRow(it, iconConfig.value.forFile(vaultId, it.path)) }
                        },
                    ).collect { step ->
                        _state.update { current ->
                            current.copy(
                                quick = step.quick ?: current.quick,
                                hits = step.hits ?: current.hits,
                                searching = step.searching,
                            )
                        }
                    }
            }
        }

        fun onQueryChange(query: String) {
            _state.update { it.copy(query = query) }
            savedState[KEY_QUERY] = query
        }

        fun clear() = onQueryChange("")

        /**
         * Searches for [initial] if it was asked for from outside -- a
         * bookmarked search, a link from another app -- and has not already
         * been.
         *
         * Once per query rather than once per screen: the screen asks again
         * whenever it is composed, and replacing what was typed since each
         * time would undo it; but a new query arriving at a search already
         * open is a new request.
         */
        fun start(initial: String) {
            if (initial.isEmpty() || savedState.get<String>(KEY_ARRIVED) == initial) return
            savedState[KEY_ARRIVED] = initial
            onQueryChange(initial)
        }

        private companion object {
            const val DEBOUNCE_MS = 200L
            const val KEY_QUERY = "search_query"
            const val KEY_ARRIVED = "search_arrived"
        }
    }
