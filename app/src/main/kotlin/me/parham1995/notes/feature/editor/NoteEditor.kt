package me.parham1995.notes.feature.editor

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.icon.LocalLucide
import me.parham1995.notes.ui.icon.LucideSet
import me.parham1995.notes.ui.icon.drawLucide
import me.parham1995.notes.ui.render.icon
import me.parham1995.notes.ui.theme.EditorLook
import me.parham1995.notes.ui.theme.EditorMetrics
import me.parham1995.notes.ui.theme.editorLook

/** The editor's text field, for a test to find. */
internal const val EDITOR_TAG = "note-editor"

/** The editor's look in the theme and at the text size being read at. */
@Composable
internal fun rememberEditorLook(): EditorLook {
    val scheme = MaterialTheme.colorScheme
    val reading = LocalReading.current
    val size = MaterialTheme.typography.bodyLarge.fontSize.value * reading.textScale
    return remember(scheme, size, reading.persianFont) { scheme.editorLook(size, reading.persianFont) }
}

/**
 * A note's Markdown, edited as text and drawn in the look of `look.md`
 * (notes-linux's `docs/specs/`).
 *
 * [value] is the file's text and nothing else. The sizes, the colours, the
 * hidden marks, the callout's panel and the quote's bar are all drawn: the
 * text is styled by a visual transformation ([EditorStyler]) and the rest is
 * painted behind it. With [livePreview] off every mark shows.
 */
@Composable
internal fun NoteEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    livePreview: Boolean = true,
    look: EditorLook = rememberEditorLook(),
) {
    val styler = remember { EditorStyler() }
    val drawn =
        remember(value.text, value.selection, look, livePreview) {
            styler.draw(value.text, value.selection, look, livePreview)
        }
    val transformation = remember(drawn) { EditorTransformation(drawn.text) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val lucide = LocalLucide.current
    val measurer = rememberTextMeasurer()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier =
            modifier
                .testTag(EDITOR_TAG)
                .drawBehind { layout?.let { drawDecor(it, drawn, look, lucide, measurer) } },
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

/** Paints what is behind the text: the caret's line, a quote's bar, a callout's panel with its icon. */
private fun DrawScope.drawDecor(
    layout: TextLayoutResult,
    drawn: EditorDrawn,
    look: EditorLook,
    lucide: LucideSet,
    measurer: TextMeasurer,
) {
    // A layout of an older text: the next one is on its way.
    if (layout.layoutInput.text.length != drawn.text.length) return
    for (decor in drawn.decor) {
        val firstLine = layout.getLineForOffset(decor.start)
        val top = layout.getLineTop(firstLine)
        val bottom = layout.getLineBottom(layout.getLineForOffset(decor.end))
        val rtl = layout.getParagraphDirection(decor.start) == ResolvedTextDirection.Rtl
        when (decor) {
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
