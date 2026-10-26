package me.parham1995.notes.feature.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.parham1995.notes.ui.theme.AccentColors
import me.parham1995.notes.ui.theme.Accents
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A graph more than one hop deep, or the whole vault: every note a dot, sized
 * by how many links it has and coloured by its top folder, every link a
 * hairline. Pinch to zoom, drag to move, tap a dot to open its note.
 *
 * The links are one path, built once per layout and drawn under a transform,
 * so moving the picture redraws a few thousand dots and one path rather than
 * re-measuring ten thousand lines. Names are drawn for the notes with the most
 * links, and for all of them once zoomed in far enough to read them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetworkView(
    network: Network,
    onOpenNote: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = Accents
    val palette = remember(accents) { palette(accents) }
    val grey = MaterialTheme.colorScheme.outline
    val edgeColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = EDGE_ALPHA)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseRadius = with(density) { NODE_MIN.toPx() }
    var scale by remember(network) { mutableFloatStateOf(1f) }
    var pan by remember(network) { mutableStateOf(Offset.Zero) }

    // In layout units -- the layout's -1..1 times UNIT -- so it is built once.
    val edges =
        remember(network) {
            Path().apply {
                network.edges.forEach { (a, b) ->
                    val from = network.nodes[a]
                    val to = network.nodes[b]
                    moveTo(from.x * UNIT, from.y * UNIT)
                    lineTo(to.x * UNIT, to.y * UNIT)
                }
            }
        }
    // The best-connected notes are named however far out the picture is.
    val named =
        remember(network) {
            network.nodes.indices
                .sortedByDescending { network.nodes[it].degree }
                .take(ALWAYS_NAMED)
                .toSet() + listOfNotNull(network.centre)
        }

    fun radiusOf(node: NetNode): Float = baseRadius * min(1f + sqrt(node.degree.toFloat()) * GROWTH, MAX_GROWTH)

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .testTag("network")
                .pointerInput(network) {
                    detectTransformGestures { _, move, zoom, _ ->
                        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                        pan += move
                    }
                }.pointerInput(network) {
                    detectTapGestures { tap ->
                        val fit = min(size.width, size.height) / 2f * FIT / UNIT
                        val middle = Offset(size.width / 2f, size.height / 2f) + pan
                        val nearest =
                            network.nodes.minByOrNull { node ->
                                hypot(
                                    middle.x + node.x * UNIT * fit * scale - tap.x,
                                    middle.y + node.y * UNIT * fit * scale - tap.y,
                                )
                            } ?: return@detectTapGestures
                        val distance =
                            hypot(
                                middle.x + nearest.x * UNIT * fit * scale - tap.x,
                                middle.y + nearest.y * UNIT * fit * scale - tap.y,
                            )
                        if (distance <= TOUCH.toPx()) onOpenNote(nearest.id)
                    }
                },
        ) {
            val fit = min(size.width, size.height) / 2f * FIT / UNIT
            val middle = Offset(size.width / 2f, size.height / 2f) + pan
            val total = fit * scale
            withTransform({
                translate(middle.x, middle.y)
                scale(total, total, Offset.Zero)
            }) {
                drawPath(edges, edgeColour, style = Stroke(width = HAIRLINE / total))
            }
            network.nodes.forEachIndexed { index, node ->
                val at = Offset(middle.x + node.x * UNIT * total, middle.y + node.y * UNIT * total)
                if (at.x < -MARGIN || at.y < -MARGIN || at.x > size.width + MARGIN || at.y > size.height + MARGIN) {
                    return@forEachIndexed
                }
                val colour = if (index == network.centre) accents.orange else palette.getOrNull(node.group) ?: grey
                val radius = radiusOf(node) * if (index == network.centre) CENTRE_GROWTH else 1f
                drawCircle(colour, radius, at)
                if (index in named || scale >= NAME_ALL_SCALE || network.nodes.size <= NAME_ALL_COUNT) {
                    val text =
                        measurer.measure(
                            node.label,
                            style = TextStyle(fontSize = LABEL_SIZE, color = onSurface),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            constraints = Constraints(maxWidth = LABEL_WIDTH.roundToPx()),
                        )
                    drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y + radius + LABEL_GAP.toPx()))
                }
            }
        }
        if (network.folders.isNotEmpty()) {
            FlowRow(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                network.folders.forEachIndexed { index, folder ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(palette[index]))
                        Text(folder, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** One colour per coloured folder, as many as [COLOURED_FOLDERS]. */
private fun palette(accents: AccentColors): List<Color> =
    listOf(
        accents.blue,
        accents.springGreen,
        accents.purple,
        accents.pink,
        accents.aqua,
        accents.red,
        accents.vividYellow,
        accents.chartreuse,
        accents.warmGrey,
    ).take(COLOURED_FOLDERS)

private const val UNIT = 1000f
private const val FIT = 0.92f
private const val EDGE_ALPHA = 0.25f
private const val HAIRLINE = 1f
private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 12f
private const val MARGIN = 40f
private const val GROWTH = 0.35f
private const val MAX_GROWTH = 4f
private const val CENTRE_GROWTH = 1.4f
private const val ALWAYS_NAMED = 30
private const val NAME_ALL_SCALE = 3f
private const val NAME_ALL_COUNT = 60
private val NODE_MIN = 3.dp
private val TOUCH = 24.dp
private val LABEL_GAP = 2.dp
private val LABEL_WIDTH = 120.dp
private val LABEL_SIZE = 10.sp
