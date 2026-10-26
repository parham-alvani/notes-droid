package me.parham1995.notes.feature.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.ui.Loading
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.theme.AccentColors
import me.parham1995.notes.ui.theme.Accents
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * What this note is connected to.
 *
 * One hop by default, drawn as a ring: it answers the question people usually
 * have, which is "what did I connect this to", and it is deterministic -- the
 * same note draws the same picture every time, so the shape becomes
 * recognisable. Direction is in the colour, because in a vault where every
 * link was typed by hand it is the interesting part: something linking *to*
 * this note is someone else's reference to it, and a mutual link is one
 * somebody meant twice.
 *
 * Two to four hops, or the whole vault, are a force layout instead
 * ([NetworkView]) -- asked for, not the default, because 2,407 notes and 9,593
 * links drawn at once look like the vault more than they answer anything
 * about it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    noteId: Long,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    viewModel: GraphViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(noteId) { viewModel.load(noteId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.centre.ifEmpty { stringResource(R.string.note_connections) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        LucideGlyph(
                            "arrow-left",
                            size = 22.dp,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ReachChips(state.reach, viewModel::reach)
            Box(Modifier.fillMaxSize()) {
                Graph(state, onOpenNote)
            }
        }
    }
}

/** One hop, two, three, four, or the whole vault. */
@Composable
private fun ReachChips(
    reach: GraphReach,
    onReach: (GraphReach) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GraphReach.CHOICES.forEach { choice ->
            FilterChip(
                selected = choice == reach,
                onClick = { onReach(choice) },
                label = {
                    Text(
                        when (choice) {
                            is GraphReach.Hops -> pluralStringResource(R.plurals.graph_hops, choice.depth, choice.depth)
                            GraphReach.Vault -> stringResource(R.string.graph_vault)
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun BoxScope.Graph(
    state: GraphUiState,
    onOpenNote: (Long) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val network = state.network
    when {
        state.loading -> Loading(Modifier.align(Alignment.Center))

        network != null && network.nodes.size > 1 ->
            NetworkView(network, onOpenNote, Modifier.fillMaxSize())

        state.nodes.isEmpty() ->
            Text(
                stringResource(R.string.graph_no_links),
                Modifier.align(Alignment.Center).padding(32.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                textAlign = TextAlign.Center,
            )

        else -> {
            // Recomputed only when the ring changes, and reused by both
            // the drawing and the hit test so a tap cannot land on a
            // node the picture does not show.
            val ring = remember(state.nodes) { state.nodes }
            val accents = Accents

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(ring) {
                        detectTapGestures { tap ->
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val radius = min(size.width, size.height) / 2f - EDGE_INSET
                            ring.forEachIndexed { index, node ->
                                val at = positionOf(index, ring.size, centre, radius)
                                if (hypot(tap.x - at.x, tap.y - at.y) <= TOUCH_RADIUS) {
                                    onOpenNote(node.id)
                                    return@detectTapGestures
                                }
                            }
                        }
                    },
            ) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val radius = min(size.width, size.height) / 2f - EDGE_INSET

                ring.forEachIndexed { index, node ->
                    val at = positionOf(index, ring.size, centre, radius)
                    val colour = node.direction.colour(accents)

                    drawLine(
                        color = colour.copy(alpha = EDGE_ALPHA),
                        start = centre,
                        end = at,
                        strokeWidth = if (node.direction == Edge.MUTUAL) MUTUAL_STROKE else EDGE_STROKE,
                    )
                    drawCircle(color = colour, radius = NODE_RADIUS, center = at)
                    label(measurer, node.label, at, radius, onSurface)
                }

                // Drawn last so the edges run underneath it.
                drawCircle(color = accents.orange, radius = CENTRE_RADIUS, center = centre)
            }

            if (state.hidden > 0) {
                Text(
                    pluralStringResource(R.plurals.graph_hidden, state.hidden, state.hidden),
                    Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }
    }
}

/** Evenly around the circle, starting at the top. */
private fun positionOf(
    index: Int,
    count: Int,
    centre: Offset,
    radius: Float,
): Offset {
    val angle = (index.toFloat() / count) * TAU - QUARTER_TURN
    return Offset(centre.x + radius * cos(angle), centre.y + radius * sin(angle))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    radius: Float,
    colour: Color,
) {
    val measured =
        measurer.measure(
            text = text,
            style = TextStyle(fontSize = LABEL_SIZE, color = colour),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints =
                androidx.compose.ui.unit
                    .Constraints(maxWidth = (radius * LABEL_WIDTH_FRACTION).toInt()),
        )
    // Pushed outward from the node so the label does not sit on the dot, and
    // clamped so a node near the edge keeps its text on screen.
    val x = (at.x - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width)
    val y = (at.y + NODE_RADIUS + LABEL_GAP).coerceAtMost(size.height - measured.size.height)
    drawText(measured, topLeft = Offset(x, y))
}

private fun Edge.colour(accents: AccentColors): Color =
    when (this) {
        Edge.OUT -> accents.blue
        Edge.IN -> accents.springGreen
        Edge.MUTUAL -> accents.purple
    }

private const val TAU = 6.2831855f
private const val QUARTER_TURN = 1.5707964f
private const val EDGE_INSET = 110f
private const val NODE_RADIUS = 14f
private const val CENTRE_RADIUS = 22f
private const val TOUCH_RADIUS = 48f
private const val EDGE_STROKE = 2f
private const val MUTUAL_STROKE = 4f
private const val EDGE_ALPHA = 0.5f
private const val LABEL_GAP = 8f
private const val LABEL_WIDTH_FRACTION = 0.9f
private val LABEL_SIZE = 11.sp
