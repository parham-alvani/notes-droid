package me.parham1995.notes.markdown

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A vault's notes and the links between them, as a graph to draw: nodes by
 * index, edges once each whichever way they were written.
 */
class NoteGraph(
    val ids: List<Long>,
    val paths: List<String>,
    /** Each link once, as a pair of node indices, the smaller first; no note links to itself. */
    val edges: List<Pair<Int, Int>>,
) {
    init {
        require(ids.size == paths.size) { "every node needs a path" }
    }

    val size: Int get() = ids.size

    /** How many links each note has, in and out together. */
    val degrees: IntArray by lazy {
        IntArray(size).also { degree ->
            edges.forEach { (a, b) ->
                degree[a]++
                degree[b]++
            }
        }
    }

    private val neighbours: List<IntArray> by lazy {
        val lists = List(size) { mutableListOf<Int>() }
        edges.forEach { (a, b) ->
            lists[a] += b
            lists[b] += a
        }
        lists.map { it.toIntArray() }
    }

    /** The notes at most [depth] links from [centre], following links either way. */
    fun within(
        centre: Int,
        depth: Int,
    ): Set<Int> {
        val seen = linkedSetOf(centre)
        var frontier = listOf(centre)
        repeat(depth) {
            val next = mutableListOf<Int>()
            for (node in frontier) {
                for (other in neighbours[node]) if (seen.add(other)) next += other
            }
            if (next.isEmpty()) return seen
            frontier = next
        }
        return seen
    }

    /** Only the notes in [keep], with the links among them, in [keep]'s order. */
    fun only(keep: Collection<Int>): NoteGraph {
        val order = keep.toList()
        val at = HashMap<Int, Int>(order.size * 2)
        order.forEachIndexed { index, node -> at[node] = index }
        return NoteGraph(
            ids = order.map { ids[it] },
            paths = order.map { paths[it] },
            edges = edges.mapNotNull { (a, b) -> at[a]?.let { x -> at[b]?.let { y -> minOf(x, y) to maxOf(x, y) } } },
        )
    }

    /** A note's top-level folder, the group it is coloured by; empty at the root. */
    fun folderOf(node: Int): String = paths[node].substringBefore('/', "")

    companion object {
        /**
         * The graph of [ids] (each at the path beside it) and [links] between
         * them by id. A link to a note not in [ids] -- another vault's, or one
         * that is gone -- and a note's link to itself are left out, and a
         * pair linked both ways is one edge.
         */
        fun of(
            ids: List<Long>,
            paths: List<String>,
            links: List<Pair<Long, Long>>,
        ): NoteGraph {
            val index = HashMap<Long, Int>(ids.size * 2)
            ids.forEachIndexed { at, id -> index[id] = at }
            val edges =
                links
                    .mapNotNull { (from, to) ->
                        val a = index[from] ?: return@mapNotNull null
                        val b = index[to] ?: return@mapNotNull null
                        if (a == b) null else minOf(a, b) to maxOf(a, b)
                    }.distinct()
            return NoteGraph(ids, paths, edges)
        }
    }
}

/**
 * A force layout -- linked notes pull together, every note pushes the ones
 * near it away -- cheap enough for a few thousand notes on a phone.
 *
 * The push is only felt within a few cells of a grid laid over the picture,
 * not from every other note, so a step costs about the number of notes and
 * links rather than its square; and the number of steps is capped and shrinks
 * as the graph grows. Notes start around a circle by their group, so a folder
 * begins together and the same vault draws the same picture every time.
 */
object ForceLayout {
    /** Positions as x, y pairs, roughly within -1..1. */
    fun layout(
        count: Int,
        edges: List<Pair<Int, Int>>,
        groups: IntArray = IntArray(count),
        iterations: Int = iterationsFor(count),
        pinned: Int? = null,
    ): FloatArray {
        val xy = FloatArray(count * 2)
        if (count == 0) return xy
        start(count, groups, xy)
        pinned?.let {
            xy[it * 2] = 0f
            xy[it * 2 + 1] = 0f
        }
        if (count == 1) return xy

        // The ideal distance between two notes, for a picture about 2 wide.
        val k = (AREA / count).let { sqrt(it) }
        val cell = k * REACH
        val dx = FloatArray(count)
        val dy = FloatArray(count)
        var temperature = INITIAL_TEMPERATURE
        val cooling = temperature / (iterations + 1)
        repeat(iterations) {
            dx.fill(0f)
            dy.fill(0f)
            repel(count, xy, k, cell, dx, dy)
            for ((a, b) in edges) {
                val ex = xy[a * 2] - xy[b * 2]
                val ey = xy[a * 2 + 1] - xy[b * 2 + 1]
                val distance = max(sqrt(ex * ex + ey * ey), MIN_DISTANCE)
                val pull = distance * distance / k / distance
                dx[a] -= ex * pull
                dy[a] -= ey * pull
                dx[b] += ex * pull
                dy[b] += ey * pull
            }
            for (node in 0 until count) {
                if (node == pinned) continue
                // A little gravity, so a loose note does not drift off.
                dx[node] -= xy[node * 2] * GRAVITY
                dy[node] -= xy[node * 2 + 1] * GRAVITY
                val length = max(sqrt(dx[node] * dx[node] + dy[node] * dy[node]), MIN_DISTANCE)
                val step = min(length, temperature)
                xy[node * 2] += dx[node] / length * step
                xy[node * 2 + 1] += dy[node] / length * step
            }
            temperature -= cooling
        }
        return normalised(xy)
    }

    /** Fewer steps for a bigger graph: the vault drawn whole is a picture, not a plot. */
    fun iterationsFor(count: Int): Int =
        when {
            count <= SMALL -> MAX_ITERATIONS
            count <= MEDIUM -> MAX_ITERATIONS / 2
            else -> MAX_ITERATIONS / 4
        }

    private fun start(
        count: Int,
        groups: IntArray,
        xy: FloatArray,
    ) {
        val groupCount = (groups.maxOrNull() ?: 0) + 1
        val random = java.util.Random(SEED)
        for (node in 0 until count) {
            val angle = (groups[node].toDouble() / groupCount) * 2 * Math.PI
            val spread = SPREAD * random.nextDouble()
            xy[node * 2] = (Math.cos(angle) * HOME + (random.nextDouble() - 0.5) * spread).toFloat()
            xy[node * 2 + 1] = (Math.sin(angle) * HOME + (random.nextDouble() - 0.5) * spread).toFloat()
        }
    }

    /** The push between notes in the same or the next grid cell. */
    private fun repel(
        count: Int,
        xy: FloatArray,
        k: Float,
        cell: Float,
        dx: FloatArray,
        dy: FloatArray,
    ) {
        val grid = HashMap<Long, MutableList<Int>>()

        fun key(
            x: Int,
            y: Int,
        ) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
        val cx = IntArray(count)
        val cy = IntArray(count)
        for (node in 0 until count) {
            cx[node] = floor(xy[node * 2] / cell).toInt()
            cy[node] = floor(xy[node * 2 + 1] / cell).toInt()
            grid.getOrPut(key(cx[node], cy[node])) { mutableListOf() } += node
        }
        val k2 = k * k
        for (node in 0 until count) {
            for (gx in cx[node] - 1..cx[node] + 1) {
                for (gy in cy[node] - 1..cy[node] + 1) {
                    val others = grid[key(gx, gy)] ?: continue
                    for (other in others) {
                        if (other == node) continue
                        val ex = xy[node * 2] - xy[other * 2]
                        val ey = xy[node * 2 + 1] - xy[other * 2 + 1]
                        val d2 = max(ex * ex + ey * ey, MIN_DISTANCE * MIN_DISTANCE)
                        val push = k2 / d2
                        dx[node] += ex * push
                        dy[node] += ey * push
                    }
                }
            }
        }
    }

    private fun normalised(xy: FloatArray): FloatArray {
        var extent = MIN_DISTANCE
        for (value in xy) extent = max(extent, abs(value))
        for (at in xy.indices) xy[at] /= extent
        return xy
    }

    private const val AREA = 4f
    private const val REACH = 2f
    private const val INITIAL_TEMPERATURE = 0.2f
    private const val MIN_DISTANCE = 0.0001f
    private const val GRAVITY = 0.05f
    private const val HOME = 0.6
    private const val SPREAD = 0.8
    private const val SEED = 1995L
    private const val SMALL = 300
    private const val MEDIUM = 1500
    private const val MAX_ITERATIONS = 200
}
