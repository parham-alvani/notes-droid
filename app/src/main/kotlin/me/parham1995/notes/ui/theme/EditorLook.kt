package me.parham1995.notes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import me.parham1995.notes.markdown.CalloutKind
import me.parham1995.notes.markdown.EditorSpan
import me.parham1995.notes.markdown.EditorStyle
import me.parham1995.notes.markdown.HighlightColor

/**
 * The editor's look, "naz, refined" (`look.md` in notes-linux's
 * `docs/specs/`, "Android: the editor"): the numbers every app shares.
 *
 * A size is times the phone's own text size. A colour says what a thing is,
 * and nothing is a border or a gradient.
 */
internal object EditorMetrics {
    /** A heading's size by its level, times the text size. */
    fun headingScale(level: Int): Float =
        when (level) {
            1 -> 1.6f
            2 -> 1.35f
            3 -> 1.12f
            4, 5 -> 1f
            else -> 0.875f
        }

    /** One weight for headings, and a lighter one for levels 5 and 6. */
    fun headingWeight(level: Int): FontWeight = if (level >= 5) FontWeight(600) else Strong

    /** Bold, and a callout's title. */
    val Strong = FontWeight(650)

    /** A line's height, times its text size; a right-to-left line has more room. */
    const val LINE_HEIGHT = 1.65f
    const val RTL_LINE_HEIGHT = 2f

    /** The highlight colour under marked text. */
    const val HIGHLIGHT_ALPHA = 0.32f

    /** A callout's panel and the hairline round it, in its kind's colour. */
    const val CALLOUT_ALPHA = 0.10f
    const val CALLOUT_HAIRLINE_ALPHA = 0.26f
    const val CALLOUT_CORNER_DP = 12

    /** A callout's icon, and the room before its title and at its sides, in em. */
    const val CALLOUT_ICON_EM = 1.1f
    const val CALLOUT_GAP_EM = 0.4f
    const val CALLOUT_SIDE_EM = 1f

    /** The bar at the side a quote's lines start, and the room after it in em. */
    const val QUOTE_BAR_DP = 3
    const val QUOTE_INDENT_EM = 1f

    /** The text colour behind the caret's line. */
    const val CURRENT_LINE_ALPHA = 0.045f

    /** A tag's colour behind it. */
    const val TAG_ALPHA = 0.14f
}

/**
 * The editor's colours on one scheme, and its text size in sp.
 *
 * Daylight, naz and naz, soft each get theirs from the scheme: [MarkupColors]
 * and [AccentColors] already say which palette a scheme is.
 */
internal data class EditorLook(
    val text: Color,
    /** Marks while they show, comments, a rule. */
    val faint: Color,
    /** The caret and a quote's bar. */
    val accent: Color,
    val codeBackground: Color,
    val tag: Color,
    val tagFill: Color,
    val markup: MarkupColors,
    val accents: AccentColors,
    val size: Float,
    /** Right-to-left lines are set in Vazirmatn. */
    val persianFont: Boolean = true,
) {
    val currentLine: Color get() = text.copy(alpha = EditorMetrics.CURRENT_LINE_ALPHA)

    fun calloutColor(kind: CalloutKind): Color = kind.accent(accents)

    /** The colour under a highlight: the plain one is the vivid yellow, as the reader has it. */
    fun highlight(color: HighlightColor?): Color =
        when (color) {
            null, HighlightColor.YELLOW -> accents.vividYellow
            HighlightColor.RED -> accents.red
            HighlightColor.ORANGE -> accents.orange
            HighlightColor.GREEN -> accents.springGreen
            HighlightColor.BLUE -> accents.blue
            HighlightColor.PURPLE -> accents.purple
        }.copy(alpha = EditorMetrics.HIGHLIGHT_ALPHA)

    /**
     * How [span] is drawn. [level] is its line's heading level, [block] is a
     * line of a code block, and [callout] its callout's kind.
     */
    fun style(
        span: EditorSpan,
        level: Int = 0,
        block: Boolean = false,
        callout: CalloutKind? = null,
    ): SpanStyle =
        when (span.style) {
            EditorStyle.HEADING ->
                SpanStyle(
                    color = markup.heading(level),
                    fontSize = (size * EditorMetrics.headingScale(level)).sp,
                    fontWeight = EditorMetrics.headingWeight(level),
                )
            EditorStyle.STRONG -> SpanStyle(color = markup.Strong, fontWeight = EditorMetrics.Strong)
            // Slanted, in the text's colour.
            EditorStyle.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
            EditorStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            EditorStyle.CODE ->
                SpanStyle(
                    color = markup.Raw,
                    fontFamily = FontFamily.Monospace,
                    background = if (block) Color.Unspecified else codeBackground,
                )
            // A tint under the text, which keeps its own colours.
            EditorStyle.HIGHLIGHT -> SpanStyle(background = highlight(span.color))
            EditorStyle.LINK -> SpanStyle(color = markup.Link, textDecoration = TextDecoration.Underline)
            EditorStyle.TAG -> SpanStyle(color = tag, background = tagFill)
            EditorStyle.MATH -> SpanStyle(color = markup.Math, fontFamily = FontFamily.Monospace)
            EditorStyle.COMMENT, EditorStyle.RULE -> SpanStyle(color = faint)
            // One colour at every level.
            EditorStyle.LIST_MARKER -> SpanStyle(color = markup.ListMarker)
            EditorStyle.CALLOUT_TITLE ->
                SpanStyle(
                    color = calloutColor(callout ?: CalloutKind.NOTE),
                    fontWeight = EditorMetrics.Strong,
                )
        }
}

/** The editor's look on this scheme, at [size] sp. */
internal fun ColorScheme.editorLook(
    size: Float,
    persianFont: Boolean = true,
): EditorLook =
    EditorLook(
        text = onBackground,
        faint = onSurfaceVariant,
        accent = primary,
        codeBackground = surfaceVariant,
        tag = onSecondaryContainer,
        tagFill = secondary.copy(alpha = EditorMetrics.TAG_ALPHA),
        markup = markup,
        accents = accents,
        size = size,
        persianFont = persianFont,
    )
