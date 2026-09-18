package me.parham1995.notes.feature.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.parham1995.notes.data.Neighbours
import me.parham1995.notes.data.VaultRepository
import javax.inject.Inject

/** One note on the graph. */
data class GraphNode(
    val id: Long,
    val label: String,
    val direction: Edge,
)

enum class Edge {
    /** This note links to it. */
    OUT,

    /** It links to this note. */
    IN,

    /** Both, which in a hand-linked vault means somebody meant it. */
    MUTUAL,
}

data class GraphUiState(
    val centre: String = "",
    val nodes: List<GraphNode> = emptyList(),
    /** How many were left off, when there are more than the ring can hold. */
    val hidden: Int = 0,
    val loading: Boolean = true,
)

@HiltViewModel
class GraphViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(GraphUiState())
        val state: StateFlow<GraphUiState> = _state.asStateFlow()

        fun load(id: Long) {
            viewModelScope.launch {
                val found = repository.neighbours(id)
                _state.value =
                    if (found == null) {
                        GraphUiState(loading = false)
                    } else {
                        found.toUiState()
                    }
            }
        }

        private fun Neighbours.toUiState(): GraphUiState {
            val both = mutual
            val nodes =
                buildList {
                    // Mutual first, then outgoing, then incoming: the ring is
                    // capped, and if something has to be left off it should be
                    // the weakest connection rather than whichever sorted last.
                    both.forEach { id ->
                        val row = outgoing.first { it.noteId == id }
                        add(GraphNode(id, row.title, Edge.MUTUAL))
                    }
                    outgoing.filterNot { it.noteId in both }.forEach {
                        add(GraphNode(it.noteId, it.title, Edge.OUT))
                    }
                    incoming.filterNot { it.noteId in both }.forEach {
                        add(GraphNode(it.noteId, it.title, Edge.IN))
                    }
                }
            return GraphUiState(
                centre = centre.title,
                nodes = nodes.take(MAX_NODES),
                hidden = (nodes.size - MAX_NODES).coerceAtLeast(0),
                loading = false,
            )
        }

        private companion object {
            /**
             * A ring of more than this is unreadable on a phone, and some notes
             * here have well over a hundred backlinks.
             */
            const val MAX_NODES = 24
        }
    }
