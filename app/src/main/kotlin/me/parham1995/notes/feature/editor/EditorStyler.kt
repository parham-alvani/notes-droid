package me.parham1995.notes.feature.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import me.parham1995.notes.markdown.CalloutKind
import me.parham1995.notes.markdown.EditorDocument
import me.parham1995.notes.markdown.EditorLine
import me.parham1995.notes.markdown.EditorLineKind
import me.parham1995.notes.markdown.EditorQuote
import me.parham1995.notes.markdown.EditorRange
import me.parham1995.notes.ui.theme.EditorLook
import me.parham1995.notes.ui.theme.EditorMetrics
import me.parham1995.notes.ui.theme.Vazirmatn

/** What is drawn behind the text. Offsets are in the note: a line's first place and its last. */
internal sealed interface EditorDecor {
    val start: Int
    val end: Int

    /** The caret's line. */
    data class CurrentLine(
        override val start: Int,
        override val end: Int,
    ) : EditorDecor

    /** A run of quote lines: a bar at the side they start. */
    data class Quote(
        override val start: Int,
        override val end: Int,
    ) : EditorDecor

    /**
     * A callout, from its title line to its last line: one panel. [name] is
     * the kind's name to draw on a title line that says nothing, and null
     * when the line has a title or shows its marks.
     */
    data class Callout(
        override val start: Int,
        override val end: Int,
        val kind: CalloutKind,
        val name: String?,
    ) : EditorDecor
}

/** A note as the editor draws it: the styled text and what goes behind it. */
internal class EditorDrawn(
    val text: AnnotatedString,
    val decor: List<EditorDecor>,
)

/**
 * Turns a note's text into what the text field draws.
 *
 * Every line is a paragraph of its own, so it can have its own line height,
 * direction and room at the side. A text field draws a paragraph break as a
 * line of its own, so the line break of each line is drawn as a zero-width
 * space that ends its paragraph, and one more ends the last line: the drawn
 * text is one character longer than the note ([EditorOffsets]). Nothing of
 * that is in the note's text.
 *
 * A line that did not change and that the selection does not touch keeps the
 * text made for it ([EditorLine.drawn]); a key press makes its own line again.
 */
internal class EditorStyler {
    private val document = EditorDocument()

    private class Rest(
        val look: EditorLook,
        val livePreview: Boolean,
        val text: AnnotatedString,
    )

    /** How many lines the last [draw] made again, for a test of the cost. */
    var lastMade = 0
        private set

    fun draw(
        text: String,
        selection: TextRange,
        look: EditorLook,
        livePreview: Boolean = true,
    ): EditorDrawn {
        document.update(text)
        val out = AnnotatedString.Builder(text.length + 1)
        val decor = ArrayList<EditorDecor>()
        val caretLine = if (selection.collapsed) document.lineAt(selection.start) else -1
        var made = 0
        var run: EditorDecor? = null
        var calloutKind: CalloutKind? = null

        for (index in 0 until document.size) {
            val line = document.line(index)
            val start = document.start(index)
            val end = start + line.length
            val touched = selection.max >= start && selection.min <= end
            // The kind a callout's lines are coloured by is its title line's.
            if (line.quote != EditorQuote.CALLOUT) calloutKind = null
            line.callout?.let { calloutKind = it.kind }

            val rest = line.drawn as? Rest
            out.append(
                when {
                    touched -> {
                        made++
                        val inLine = EditorRange(selection.min - start, selection.max - start)
                        make(
                            text,
                            start,
                            line,
                            look,
                            calloutKind,
                            if (livePreview) line.hidden(inLine) else emptyList(),
                        )
                    }
                    rest != null && rest.look == look && rest.livePreview == livePreview -> rest.text
                    else -> {
                        made++
                        val hidden = if (livePreview) line.hidden(null) else emptyList()
                        make(text, start, line, look, calloutKind, hidden).also {
                            line.drawn =
                                Rest(look, livePreview, it)
                        }
                    }
                },
            )

            run = extend(run, line, start, end, touched || !livePreview, decor)
            if (index == caretLine) decor += EditorDecor.CurrentLine(start, end)
        }
        run?.let { decor += it }
        lastMade = made
        return EditorDrawn(out.toAnnotatedString(), decor)
    }

    /** The run of quote or callout lines [line] continues or starts, with the one before it added to [decor] when it ended. */
    private fun extend(
        run: EditorDecor?,
        line: EditorLine,
        start: Int,
        end: Int,
        raw: Boolean,
        decor: MutableList<EditorDecor>,
    ): EditorDecor? {
        val callout = line.callout
        return when {
            callout != null -> {
                run?.let { decor += it }
                EditorDecor.Callout(start, end, callout.kind, callout.title.takeIf { !callout.titled && !raw })
            }
            line.quote == EditorQuote.CALLOUT && run is EditorDecor.Callout -> run.copy(end = end)
            line.quote == EditorQuote.QUOTE && run is EditorDecor.Quote -> run.copy(end = end)
            line.quote == EditorQuote.QUOTE -> {
                run?.let { decor += it }
                EditorDecor.Quote(start, end)
            }
            else -> {
                run?.let { decor += it }
                null
            }
        }
    }

    private fun make(
        text: String,
        start: Int,
        line: EditorLine,
        look: EditorLook,
        callout: CalloutKind?,
        hidden: List<EditorRange>,
    ): AnnotatedString {
        val length = line.length
        val out = AnnotatedString.Builder(length + 1)
        out.append(text, start, start + length)
        out.append(PARAGRAPH_END)
        out.addStyle(paragraph(line, look), 0, length + 1)
        if (line.rtl && look.persianFont) out.addStyle(SpanStyle(fontFamily = Vazirmatn), 0, length)

        val block = line.kind == EditorLineKind.CODE || line.kind == EditorLineKind.FENCE
        for (span in line.spans) {
            if (span.start < span.end) out.addStyle(look.style(span, line.level, block, callout), span.start, span.end)
        }
        // A mark that shows is in the faint colour; one that does not has no width.
        for (mark in line.marks) {
            if (mark.start < mark.end) out.addStyle(SpanStyle(color = look.faint), mark.start, mark.end)
        }
        for (mark in hidden) {
            if (mark.start < mark.end) out.addStyle(NO_WIDTH, mark.start, mark.end)
        }
        return out.toAnnotatedString()
    }

    private fun paragraph(
        line: EditorLine,
        look: EditorLook,
    ): ParagraphStyle {
        val scale = if (line.kind == EditorLineKind.HEADING) EditorMetrics.headingScale(line.level) else 1f
        val height = if (line.rtl) EditorMetrics.RTL_LINE_HEIGHT else EditorMetrics.LINE_HEIGHT
        val indent =
            when {
                // Room for the icon before the title.
                line.callout != null ->
                    TextIndent(
                        firstLine =
                            (
                                EditorMetrics.CALLOUT_SIDE_EM + EditorMetrics.CALLOUT_ICON_EM +
                                    EditorMetrics.CALLOUT_GAP_EM
                            ).em,
                        restLine = EditorMetrics.CALLOUT_SIDE_EM.em,
                    )
                line.quote == EditorQuote.CALLOUT ->
                    TextIndent(
                        EditorMetrics.CALLOUT_SIDE_EM.em,
                        EditorMetrics.CALLOUT_SIDE_EM.em,
                    )
                line.quote == EditorQuote.QUOTE ->
                    TextIndent(
                        EditorMetrics.QUOTE_INDENT_EM.em,
                        EditorMetrics.QUOTE_INDENT_EM.em,
                    )
                else -> null
            }
        return ParagraphStyle(
            lineHeight = (look.size * scale * height).sp,
            lineHeightStyle = FULL_LINE,
            textDirection = TextDirection.Content,
            textIndent = indent,
        )
    }

    private companion object {
        /** Stands for a line's break: it ends the line's paragraph and has no width. */
        const val PARAGRAPH_END = '\u200B'

        /** A mark that is not shown: it is in the text and takes no room. */
        val NO_WIDTH = SpanStyle(fontSize = 0.sp)

        /** The line's height is the whole line's, the first and the last one too. */
        val FULL_LINE = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    }
}
