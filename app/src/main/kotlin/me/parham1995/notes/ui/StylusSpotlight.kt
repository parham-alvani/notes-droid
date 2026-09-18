package me.parham1995.notes.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A soft mark that follows the pen while it hovers, without touching the page.
 *
 * The S Pen reports where it is before it is put down -- `ACTION_HOVER_MOVE`,
 * which Compose delivers as a move from a pointer that is not pressed. Nothing
 * in a reader responds to that, so hovering over a long note gives no feedback
 * at all and the pen is just a stick held over glass.
 *
 * What this draws is a place-keeper: the line being read, marked by where the
 * hand already is. It is deliberately faint and deliberately not a cursor --
 * it should be findable when looked for and invisible when not.
 *
 * Only a stylus is followed. A finger hovering is not a thing, and a mouse
 * pointer already draws itself.
 */
@Composable
fun StylusSpotlight(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tint: Color,
    radius: Dp = SPOTLIGHT_RADIUS,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }

    var hover by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // The initial pass, so the page underneath still gets
                        // every event: this watches, it does not take part.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pen = event.changes.firstOrNull { it.type == PointerType.Stylus }
                        hover =
                            when {
                                pen == null || event.type == PointerEventType.Exit -> null
                                // Once the pen is down the finger-equivalent
                                // gestures take over and a second mark under
                                // them is just clutter.
                                pen.pressed -> null
                                else -> pen.position
                            }
                    }
                }
            }.drawWithContent {
                drawContent()
                hover?.let { at ->
                    val px = radius.toPx()
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colors = listOf(tint.copy(alpha = CENTRE_ALPHA), Color.Transparent),
                                center = at,
                                radius = px,
                            ),
                        radius = px,
                        center = at,
                    )
                }
            },
    ) {
        content()
    }
}

private val SPOTLIGHT_RADIUS = 56.dp
private const val CENTRE_ALPHA = 0.16f
