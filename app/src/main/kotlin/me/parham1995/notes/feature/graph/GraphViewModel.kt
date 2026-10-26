package me.parham1995.notes.feature.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.Neighbours
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.markdown.ForceLayout
import me.parham1995.notes.markdown.NoteGraph
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

/** How far out the graph reaches: a number of hops, or the whole vault. */
sealed interface GraphReach {
    data class Hops(
        val depth: Int,
    ) : GraphReach

    data object Vault : GraphReach

    companion object {
        const val MAX_DEPTH = 4
        val CHOICES: List<GraphReach> = (1..MAX_DEPTH).map(::Hops) + Vault
    }
}

/** A note in the laid-out network. */
data class NetNode(
    val id: Long,
    val label: String,
    /** Index into the coloured folders, or -1 for a folder too small to have a colour. */
    val group: Int,
    val degree: Int,
    val x: Float,
    val y: Float,
)

/** A graph deeper than one hop, laid out: where each note sits, and the links between them. */
class Network(
    val nodes: List<NetNode>,
    val edges: List<Pair<Int, Int>>,
    /** The note the graph was opened from, when it is in it. */
    val centre: Int?,
    /** The coloured top folders, biggest first. */
    val folders: List<String>,
)

data class GraphUiState(
    val centre: String = "",
    val nodes: List<GraphNode> = emptyList(),
    /** How many were left off, when there are more than the ring can hold. */
    val hidden: Int = 0,
    val loading: Boolean = true,
    val reach: GraphReach = GraphReach.Hops(1),
    /** Set for every reach but one hop, which is the ring. */
    val network: Network? = null,
)

@HiltViewModel
class GraphViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(GraphUiState())
        val state: StateFlow<GraphUiState> = _state.asStateFlow()

        private var noteId: Long = 0
        private var job: Job? = null

        fun load(id: Long) {
            noteId = id
            reach(_state.value.reach)
        }

        /** Draws the graph [reach] out from the note; the layout is worked out off the main thread. */
        fun reach(reach: GraphReach) {
            job?.cancel()
            _state.update { it.copy(reach = reach, loading = true) }
            job =
                viewModelScope.launch {
                    val ring = repository.neighbours(noteId)
                    val base = ring?.toUiState() ?: GraphUiState(loading = false)
                    val network =
                        if (reach == GraphReach.Hops(1)) {
                            null
                        } else {
                            repository.linkGraph(noteId)?.let { (graph, centre) ->
                                withContext(Dispatchers.Default) { network(graph, centre, reach) }
                            }
                        }
                    _state.value = base.copy(reach = reach, network = network, loading = false)
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

/** How many top folders get a colour of their own; the rest are grey. */
internal const val COLOURED_FOLDERS = 9

/**
 * [graph] cut to [reach] around [centre] (an index, -1 when the note is not
 * in it) and laid out. Pure, so it can be tested and run on any thread.
 */
internal fun network(
    graph: NoteGraph,
    centre: Int,
    reach: GraphReach,
): Network {
    val part =
        when (reach) {
            is GraphReach.Hops -> graph.only(if (centre >= 0) graph.within(centre, reach.depth) else emptySet())
            GraphReach.Vault -> graph
        }
    // `within` puts the centre first, so a cut part has it at 0.
    val middle =
        when {
            reach is GraphReach.Hops -> 0.takeIf { part.size > 0 }
            centre >= 0 -> centre
            else -> null
        }
    val sizes = (0 until part.size).groupingBy(part::folderOf).eachCount()
    val folders =
        sizes.entries
            .filter { it.key.isNotEmpty() }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(COLOURED_FOLDERS)
            .map { it.key }
    val groupOf = folders.withIndex().associate { (index, folder) -> folder to index }
    val groups = IntArray(part.size) { groupOf[part.folderOf(it)] ?: -1 }
    val xy =
        ForceLayout.layout(
            count = part.size,
            edges = part.edges,
            groups = IntArray(part.size) { groups[it] + 1 },
            pinned = middle.takeIf { reach is GraphReach.Hops },
        )
    val degrees = part.degrees
    return Network(
        nodes =
            (0 until part.size).map { at ->
                NetNode(
                    id = part.ids[at],
                    label = part.paths[at].substringAfterLast('/').removeSuffix(".md"),
                    group = groups[at],
                    degree = degrees[at],
                    x = xy[at * 2],
                    y = xy[at * 2 + 1],
                )
            },
        edges = part.edges,
        centre = middle,
        folders = folders,
    )
}
