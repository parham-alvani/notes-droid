package me.parham1995.notes.feature.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.parham1995.notes.markdown.TaskStatuses
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.LocalTaskStatuses
import me.parham1995.notes.ui.icon.LocalLucide
import me.parham1995.notes.ui.icon.LucideSet
import me.parham1995.notes.ui.icon.drawLucide
import me.parham1995.notes.ui.render.icon
import me.parham1995.notes.ui.theme.EditorLook
import me.parham1995.notes.ui.theme.EditorMetrics
import me.parham1995.notes.ui.theme.editorLook
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** The editor's text field, for a test to find. */
internal const val EDITOR_TAG = "note-editor"

/** The editor's look in the theme and at the text size being read at. */
@Composable
internal fun rememberEditorLook(): EditorLook {
    val scheme = MaterialTheme.colorScheme
    val reading = LocalReading.current
    val size = MaterialTheme.typography.bodyLarge.fontSize.value * reading.textScale
    // How wide a monospace letter is on this phone: the letter that keeps a task box's room is sized by it.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val advance =
        remember(measurer, density) {
            val wide = measurer.measure("-", TextStyle(fontFamily = FontFamily.Monospace, fontSize = MEASURED_SP.sp))
            wide.size.width / with(density) { MEASURED_SP.sp.toPx() }
        }
    return remember(scheme, size, reading.persianFont, advance) {
        val scale = EditorMetrics.BOX_EM / (advance.takeIf { it > 0f } ?: EditorMetrics.MONOSPACE_ADVANCE)
        scheme.editorLook(size, reading.persianFont, scale)
    }
}

/** Large, so the measured width is not rounded to a whole pixel of a small letter. */
private const val MEASURED_SP = 100

/** The phone's day, which changes at midnight: a due date is late against it, and nothing is asked on a key press. */
@Composable
internal fun rememberToday(): LocalDate {
    val today by produceState(LocalDate.now()) {
        while (true) {
            val now = LocalDateTime.now()
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 1)
            value = LocalDate.now()
        }
    }
    return today
}

/**
 * A note's Markdown, edited as text and drawn in the look of `look.md`
 * (notes-linux's `docs/specs/`).
 *
 * [value] is the file's text and nothing else. The sizes, the colours, the
 * hidden marks, the callout's panel and the quote's bar are all drawn: the
 * text is styled by a visual transformation ([EditorStyler]) and the rest is
 * painted behind it. With [livePreview] off every mark shows, and no box or
 * chip is drawn.
 *
 * A tap on a task's box is [onTask] with a place in the task's line, and a
 * long press is [onTaskMenu]. Neither reaches the text field, so the caret
 * stays where it was and the keyboard is not opened. With no [onTask] a tap
 * on a box is a tap in the text.
 */
@Suppress("LongParameterList")
@Composable
internal fun NoteEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    livePreview: Boolean = true,
    look: EditorLook = rememberEditorLook(),
    today: LocalDate = rememberToday(),
    statuses: TaskStatuses = LocalTaskStatuses.current,
    onTask: ((at: Int) -> Unit)? = null,
    onTaskMenu: ((at: Int) -> Unit)? = null,
) {
    val styler = remember { EditorStyler() }
    val drawn =
        remember(value.text, value.selection, look, livePreview, today, statuses) {
            styler.draw(value.text, value.selection, look, livePreview, today, statuses)
        }
    val transformation = remember(drawn) { EditorTransformation(drawn.text) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val lucide = LocalLucide.current
    val measurer = rememberTextMeasurer()
    // The part of the field on screen, top to bottom: only what is seen is painted.
    var seen by remember { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val taps = rememberUpdatedState(TaskTaps(layout, drawn, look, onTask, onTaskMenu))

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .testTag(EDITOR_TAG)
                .onGloballyPositioned {
                    val top = it.positionInRoot().y
                    val bounds = it.boundsInRoot()
                    seen = (bounds.top - top)..(bounds.bottom - top)
                }
                // Before the text field's own taps, so a tap on a box is not one in the text.
                .pointerInput(Unit) { taskTaps { taps.value } }
                .drawBehind { layout?.let { drawDecor(it, drawn, look, lucide, measurer, seen) } },
        textStyle = TextStyle(color = look.text, fontSize = look.size.sp),
        // The caret is the accent.
        cursorBrush = SolidColor(look.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        visualTransformation = transformation,
        onTextLayout = { layout = it },
    )
}

/**
 * The note's text as [EditorStyler] drew it: the same characters at the same
 * places, and one more at the end, which is no place in the note.
 */
internal class EditorTransformation(
    private val drawn: AnnotatedString,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // Drawn from another text than the field's: show the field's, plain.
        if (drawn.length != text.length + 1) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(drawn, EditorOffsets(text.length))
    }
}

/** A place in the note is the same place in the drawn text; the drawn text's last place is the note's end. */
internal class EditorOffsets(
    private val length: Int,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = offset

    override fun transformedToOriginal(offset: Int): Int = minOf(offset, length)
}

/** What a tap on a task's box needs: where the boxes are, and what a tap and a long press do. */
internal class TaskTaps(
    val layout: TextLayoutResult?,
    val drawn: EditorDrawn,
    val look: EditorLook,
    val onTask: ((Int) -> Unit)?,
    val onTaskMenu: ((Int) -> Unit)?,
)

/**
 * A tap or a long press on a task's box. The press is taken before the text
 * field sees it, so the caret does not move and the keyboard does not open.
 * A finger that moves away is a scroll, and is left to it.
 */
private suspend fun PointerInputScope.taskTaps(now: () -> TaskTaps) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val taps = now()
        val onTask = taps.onTask ?: return@awaitEachGesture
        val layout = taps.layout ?: return@awaitEachGesture
        if (layout.layoutInput.text.length != taps.drawn.text.length) return@awaitEachGesture
        val em =
            taps.look.size.sp
                .toPx()
        val box =
            boxAt(layout, taps.drawn.boxes, down.position, em, EditorMetrics.BOX_TARGET_DP.dp.toPx())
                ?: return@awaitEachGesture
        down.consume()
        var lifted = false
        var left = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (!lifted && !left) {
                val change =
                    awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                when {
                    change.changedToUp() -> {
                        change.consume()
                        lifted = true
                    }
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> left = true
                }
            }
        }
        when {
            lifted -> onTask(box.start)
            left -> Unit
            else -> {
                // Held: the status sheet. The finger's lift is not a tap in the text.
                (taps.onTaskMenu ?: onTask)(box.start)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
    }
}

/** Where a task's box is drawn: [EditorMetrics.BOX_EM] square in the room its letter keeps, its bottom under the baseline. */
internal fun boxRect(
    layout: TextLayoutResult,
    box: EditorDecor.TaskBox,
    em: Float,
): Rect {
    // The room is two letters wide: the box's marks at this place and the next.
    val first = layout.getBoundingBox(box.start)
    val second = layout.getBoundingBox(box.start + 1)
    val side = EditorMetrics.BOX_EM * em
    val bottom = layout.getLineBaseline(layout.getLineForOffset(box.start)) + EditorMetrics.BOX_DROP_EM * em
    val left = (minOf(first.left, second.left) + maxOf(first.right, second.right) - side) / 2
    return Rect(left, bottom - side, left + side, bottom)
}

/**
 * The box a press at [position] is on: the nearest one whose target holds
 * it. The target is the box grown to [target] a side, when it is smaller.
 * Only the boxes of the lines round the press are asked.
 */
internal fun boxAt(
    layout: TextLayoutResult,
    boxes: List<EditorDecor.TaskBox>,
    position: Offset,
    em: Float,
    target: Float,
): EditorDecor.TaskBox? {
    if (boxes.isEmpty()) return null
    val first = layout.getLineForVerticalPosition(position.y - target / 2)
    val last = layout.getLineForVerticalPosition(position.y + target / 2)
    val from = layout.getLineStart(first)
    val to = layout.getLineEnd(last)
    var index = boxes.binarySearch { it.start.compareTo(from) }.let { if (it < 0) -it - 1 else it }
    var best: EditorDecor.TaskBox? = null
    var nearest = Float.MAX_VALUE
    while (index < boxes.size && boxes[index].start <= to) {
        val box = boxes[index++]
        val rect = boxRect(layout, box, em)
        val grow = maxOf(0f, (target - rect.width) / 2)
        val distance = (rect.center - position).getDistance()
        if (rect.inflate(grow).contains(position) && distance < nearest) {
            best = box
            nearest = distance
        }
    }
    return best
}

/**
 * Paints what is behind the text: the caret's line, a quote's bar, a
 * callout's panel with its icon, a task's box and a date chip's pill. Only
 * what is between the top and the bottom of [seen] is painted.
 */
@Suppress("LongParameterList")
private fun DrawScope.drawDecor(
    layout: TextLayoutResult,
    drawn: EditorDrawn,
    look: EditorLook,
    lucide: LucideSet,
    measurer: TextMeasurer,
    seen: ClosedFloatingPointRange<Float>?,
) {
    // A layout of an older text: the next one is on its way.
    if (layout.layoutInput.text.length != drawn.text.length) return
    val em = look.size.sp.toPx()
    for (decor in drawn.decor) {
        val firstLine = layout.getLineForOffset(decor.start)
        val top = layout.getLineTop(firstLine)
        val bottom = layout.getLineBottom(layout.getLineForOffset(decor.end))
        if (seen != null && (bottom < seen.start - em || top > seen.endInclusive + em)) continue
        val rtl = layout.getParagraphDirection(decor.start) == ResolvedTextDirection.Rtl
        when (decor) {
            is EditorDecor.TaskBox ->
                lucide.glyph(decor.symbol.icon)?.let { glyph ->
                    val rect = boxRect(layout, decor, em)
                    drawLucide(glyph, look.taskColor(decor.symbol.tint), rect.topLeft, rect.width)
                }

            is EditorDecor.Chip -> drawChip(layout, decor, em)

            is EditorDecor.CurrentLine ->
                drawRect(look.currentLine, Offset(0f, top), Size(size.width, bottom - top))

            is EditorDecor.Quote -> {
                // At the side the lines start.
                val bar = EditorMetrics.QUOTE_BAR_DP.dp.toPx()
                drawRoundRect(
                    color = look.accent,
                    topLeft = Offset(if (rtl) size.width - bar else 0f, top),
                    size = Size(bar, bottom - top),
                    cornerRadius = CornerRadius(bar / 2),
                )
            }

            is EditorDecor.Callout ->
                drawCallout(
                    decor,
                    top,
                    bottom,
                    layout.getLineBottom(firstLine),
                    rtl,
                    look,
                    lucide,
                    measurer,
                )
        }
    }
}

/**
 * A chip's pill: its colour behind the emoji and the day, with room at each
 * side, as high as the text's line. A chip that runs over a line's end has a
 * pill on each line.
 */
private fun DrawScope.drawChip(
    layout: TextLayoutResult,
    chip: EditorDecor.Chip,
    em: Float,
) {
    val room = EditorMetrics.CHIP_ROOM_EM * em
    val height = EditorMetrics.CHIP_HEIGHT * EditorMetrics.CHIP_SCALE * em
    val fill = chip.color.copy(alpha = chip.color.alpha * EditorMetrics.CHIP_ALPHA)

    fun pill(
        line: Int,
        left: Float,
        right: Float,
    ) {
        if (right <= left) return
        val middle = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
        drawRoundRect(
            color = fill,
            topLeft = Offset(left - room, middle - height / 2),
            size = Size(right - left + 2 * room, height),
            cornerRadius = CornerRadius(height / 2),
        )
    }
    var line = -1
    var left = 0f
    var right = 0f
    for (offset in chip.start until chip.end) {
        val at = layout.getLineForOffset(offset)
        val bounds = layout.getBoundingBox(offset)
        if (at != line) {
            if (line >= 0) pill(line, left, right)
            line = at
            left = bounds.left
            right = bounds.right
        } else {
            left = minOf(left, bounds.left)
            right = maxOf(right, bounds.right)
        }
    }
    if (line >= 0) pill(line, left, right)
}

/** One panel for the callout's lines: a tint and a hairline in its kind's colour, no bar, with the icon on the title's line. */
@Suppress("LongParameterList")
private fun DrawScope.drawCallout(
    callout: EditorDecor.Callout,
    top: Float,
    bottom: Float,
    titleBottom: Float,
    rtl: Boolean,
    look: EditorLook,
    lucide: LucideSet,
    measurer: TextMeasurer,
) {
    val color = look.calloutColor(callout.kind)
    val corner = CornerRadius(EditorMetrics.CALLOUT_CORNER_DP.dp.toPx())
    val hairline = 1.dp.toPx()
    val panel = Size(size.width, bottom - top)
    drawRoundRect(color.copy(alpha = EditorMetrics.CALLOUT_ALPHA), Offset(0f, top), panel, corner)
    drawRoundRect(
        color = color.copy(alpha = EditorMetrics.CALLOUT_HAIRLINE_ALPHA),
        topLeft = Offset(hairline / 2, top + hairline / 2),
        size = Size(panel.width - hairline, panel.height - hairline),
        cornerRadius = corner,
        style = Stroke(hairline),
    )

    val em = look.size.sp.toPx()
    val side = EditorMetrics.CALLOUT_SIDE_EM * em
    val icon = EditorMetrics.CALLOUT_ICON_EM * em
    val middle = (top + titleBottom) / 2
    lucide.glyph(callout.kind.icon())?.let { glyph ->
        val x = if (rtl) size.width - side - icon else side
        drawLucide(glyph, color, Offset(x, middle - icon / 2), icon)
    }
    // A title line that says nothing reads the kind's name. It is drawn, never written.
    callout.name?.let { name ->
        val style = TextStyle(color = color, fontSize = look.size.sp, fontWeight = EditorMetrics.Strong)
        val text = measurer.measure(name, style, maxLines = 1)
        val before = side + icon + EditorMetrics.CALLOUT_GAP_EM * em
        val x = if (rtl) size.width - before - text.size.width else before
        drawText(text, topLeft = Offset(x, middle - text.size.height / 2f))
    }
}
