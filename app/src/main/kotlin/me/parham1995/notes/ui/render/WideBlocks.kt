package me.parham1995.notes.ui.render

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import me.parham1995.notes.markdown.WideBlock
import me.parham1995.notes.ui.WideRoom
import kotlin.math.roundToInt

/**
 * Lets a wide block grow past the reading column, by `wide-blocks.md` in
 * notes-linux's `docs/specs/`: as wide as it is ([WideBlock.place]'s `N`, its
 * max intrinsic width) up to the room's limit, and centred on the column,
 * which is the pane's centre. A block that fits, or a window with no room
 * beside the column, is measured as before.
 *
 * The block says it is wider than the column it was given, and Compose then
 * centres it on that column by itself; its bounds are the wide ones, so a tap
 * in the part past the column still reaches it. The list it sits in clips to
 * the pane, not to the column.
 */
internal fun Modifier.growPast(room: WideRoom): Modifier =
    layout { measurable, constraints ->
        val place =
            if (constraints.hasBoundedWidth) {
                WideBlock.place(
                    natural = measurable.maxIntrinsicWidth(Constraints.Infinity).toFloat(),
                    measure = room.measure.toPx(),
                    pane = room.pane.toPx(),
                    side = room.side.toPx(),
                    limit = room.limit.toPx(),
                )
            } else {
                null
            }
        val width = place?.width?.roundToInt()
        val placeable =
            measurable.measure(if (width == null) constraints else constraints.copy(minWidth = width, maxWidth = width))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/**
 * Says how wide the content is of itself, for a block whose own drawing
 * cannot: a picture is drawn to fill whatever it is given, so asked its width
 * it answers with the width it was last drawn at. [width] is the picture's own
 * (the size asked for, or the file's), and 0 when it is not known, which keeps
 * the block in the column. A caption says 0 too, so its words do not widen a
 * figure.
 */
internal fun Modifier.naturalWidth(width: Dp): Modifier = this then NaturalWidthElement(width)

private data class NaturalWidthElement(
    val width: Dp,
) : ModifierNodeElement<NaturalWidthNode>() {
    override fun create() = NaturalWidthNode(width)

    override fun update(node: NaturalWidthNode) {
        node.width = width
        // Whoever asked for the width (the wide block) is measured again.
        node.invalidateMeasurement()
    }
}

private class NaturalWidthNode(
    var width: Dp,
) : Modifier.Node(),
    LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(
        measurable: IntrinsicMeasurable,
        height: Int,
    ): Int = width.roundToPx()
}
