package me.parham1995.notes.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import me.parham1995.notes.markdown.CalloutKind
import me.parham1995.notes.markdown.EditorChip
import me.parham1995.notes.markdown.EditorDocument
import me.parham1995.notes.markdown.EditorLine
import me.parham1995.notes.markdown.EditorLineKind
import me.parham1995.notes.markdown.EditorQuote
import me.parham1995.notes.markdown.EditorRange
import me.parham1995.notes.markdown.EditorTasks
import me.parham1995.notes.markdown.TaskStatuses
import me.parham1995.notes.markdown.TaskSymbol
import me.parham1995.notes.ui.theme.EditorLook
import me.parham1995.notes.ui.theme.EditorMetrics
import me.parham1995.notes.ui.theme.Vazirmatn
import java.time.LocalDate

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

    /**
     * A task's box. [start] is the first of the two letters of the task's
     * marks that keep the box's room, and a place in the task's line for a
     * tap to name it by.
     */
    data class TaskBox(
        override val start: Int,
        override val end: Int,
        val symbol: TaskSymbol,
    ) : EditorDecor

    /** A date chip: a pill in [color] behind the emoji and the day. */
    data class Chip(
        override val start: Int,
        override val end: Int,
        val color: Color,
    ) : EditorDecor
}

/**
 * A note as the editor draws it: the styled text and what goes behind it.
 * [boxes] are the task boxes in the order of the text, for a tap to find one.
 */
internal class EditorDrawn(
    val text: AnnotatedString,
    val decor: List<EditorDecor>,
    val boxes: List<EditorDecor.TaskBox> = emptyList(),
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
 *
 * A task's box and its date chips are drawn the same way: the box's marks
 * take no room but one letter that keeps the box's, a chip's text is smaller
 * and in its colour, and the icon and the pill are painted behind
 * ([EditorDecor.TaskBox], [EditorDecor.Chip]). The note's text is as written.
 */
internal class EditorStyler {
    private val document = EditorDocument()

    /** What every line is drawn with. One object while nothing of it changes, so a line asks only whether it is the same one. */
    private class Setup(
        val look: EditorLook,
        val livePreview: Boolean,
        val today: LocalDate,
        val statuses: TaskStatuses,
    )

    /** One line made: its text, and its box and chips at their places in the line. */
    private class Made(
        val setup: Setup,
        val text: AnnotatedString,
        val box: TaskSymbol?,
        val boxAt: Int,
        val chips: List<EditorDecor.Chip>,
    )

    private var setup: Setup? = null

    /** How many lines the last [draw] made again, for a test of the cost. */
    var lastMade = 0
        private set

    /**
     * Draws [text]. [today] is the day a due date is late against, and
     * [statuses] the vault's: with neither given, nothing is late and the
     * statuses are the four every vault has.
     */
    @Suppress("LongParameterList")
    fun draw(
        text: String,
        selection: TextRange,
        look: EditorLook,
        livePreview: Boolean = true,
        today: LocalDate = LocalDate.MIN,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): EditorDrawn {
        document.update(text)
        val with =
            setup?.takeIf {
                it.look == look && it.livePreview == livePreview && it.today == today && it.statuses == statuses
            } ?: Setup(look, livePreview, today, statuses).also { setup = it }
        val out = AnnotatedString.Builder(text.length + 1)
        val decor = ArrayList<EditorDecor>()
        val boxes = ArrayList<EditorDecor.TaskBox>()
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

            val rest = (line.drawn as? Made)?.takeIf { it.setup === with }
            val now =
                when {
                    touched -> {
                        made++
                        make(
                            text,
                            start,
                            line,
                            with,
                            calloutKind,
                            EditorRange(
                                selection.min - start,
                                selection.max - start,
                            ),
                        )
                    }
                    rest != null -> rest
                    else -> {
                        made++
                        make(text, start, line, with, calloutKind, null).also { line.drawn = it }
                    }
                }
            out.append(now.text)

            run = extend(run, line, start, end, touched || !livePreview, decor)
            if (index == caretLine) decor += EditorDecor.CurrentLine(start, end)
            now.box?.let {
                val box = EditorDecor.TaskBox(start + now.boxAt, start + now.boxAt + 1, it)
                decor += box
                boxes += box
            }
            for (chip in now.chips) decor += chip.copy(start = start + chip.start, end = start + chip.end)
        }
        run?.let { decor += it }
        lastMade = made
        return EditorDrawn(out.toAnnotatedString(), decor, boxes)
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

    /** Makes [line], which starts at [start] of [text]. [selection] is the selection in the line when it touches it. */
    @Suppress("LongParameterList")
    private fun make(
        text: String,
        start: Int,
        line: EditorLine,
        setup: Setup,
        callout: CalloutKind?,
        selection: EditorRange?,
    ): Made {
        val look = setup.look
        val hidden = if (setup.livePreview) line.hidden(selection) else emptyList()
        val length = line.length
        // With live preview off no part is drawn: a task is its marks, in the colours of its text.
        val task = line.task?.takeIf { setup.livePreview }
        val chips = task?.chips?.filter { EditorTasks.chipDrawn(it, selection) }.orEmpty()
        val out = AnnotatedString.Builder(length + 1)
        // After a right-to-left letter a day would read `30-10-2026`: its chips are set left to right.
        val leftToRight = chips.isNotEmpty() && hasRightToLeft(text, start, start + length)
        if (leftToRight) {
            out.append(
                withLetters(text, start, length, chips),
            )
        } else {
            out.append(text, start, start + length)
        }
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
        var box: TaskSymbol? = null
        var pills: ArrayList<EditorDecor.Chip>? = null
        if (task != null) {
            val struck = EditorTasks.isStruck(task.status, setup.statuses)
            if (struck) {
                // The chips and the reason are not in what is struck.
                val style = SpanStyle(color = look.done, textDecoration = TextDecoration.LineThrough)
                for (range in task.text) out.addStyle(style, range.start, range.end)
            }
            if (EditorTasks.boxDrawn(task, selection)) {
                // Two letters keep the box's room and the rest of the marks take none.
                out.addStyle(boxRoom(look), task.box.start, task.box.start + BOX_LETTERS)
                out.addStyle(NO_WIDTH, task.box.start + BOX_LETTERS, task.box.end)
                box = EditorTasks.drawing(task.status, setup.statuses)
            }
            var before = -1
            for (chip in chips) {
                val late = EditorTasks.isLate(chip, task.status, setup.today, setup.statuses)
                val color =
                    look
                        .chipColor(
                            chip.kind,
                            late,
                        ).let { if (struck) it.copy(alpha = EditorMetrics.CHIP_CLOSED_ALPHA) else it }
                out.addStyle(
                    SpanStyle(
                        color = color,
                        fontSize = (look.size * EditorMetrics.CHIP_SCALE).sp,
                        fontWeight = if (late) EditorMetrics.Late else null,
                        textDecoration = TextDecoration.None,
                    ),
                    chip.start,
                    chip.end,
                )
                // The space before a chip is as wide as the pill's room, and the gap after another pill.
                if (chip.start > 0 && text[start + chip.start - 1] == ' ') {
                    val room =
                        if (before == chip.start - 1) {
                            2 * EditorMetrics.CHIP_ROOM_EM + EditorMetrics.CHIP_GAP_EM - EditorMetrics.SPACE_EM
                        } else {
                            EditorMetrics.CHIP_ROOM_EM
                        }
                    out.addStyle(SpanStyle(letterSpacing = room.em), chip.start - 1, chip.start)
                }
                if (leftToRight) leftToRight(out, text, start, chip, line.rtl, look)
                pills = (pills ?: ArrayList()).also { it += EditorDecor.Chip(chip.start, chip.end, color) }
                before = chip.end
            }
        }
        return Made(setup, out.toAnnotatedString(), box, task?.box?.start ?: 0, pills ?: emptyList())
    }

    /**
     * Styles the letter [withLetters] put in [chip] in place of its space.
     * In a line that reads left to right it is as wide as the space was. In
     * one that reads right to left ([rtl]) the letter is at the day's far
     * side, so it takes no room and the room after the emoji is the emoji's
     * own.
     */
    @Suppress("LongParameterList")
    private fun leftToRight(
        out: AnnotatedString.Builder,
        text: String,
        start: Int,
        chip: EditorChip,
        rtl: Boolean,
        look: EditorLook,
    ) {
        val space = text.indexOf(' ', start + chip.start) - start
        if (space !in chip.start until chip.end) return
        if (rtl) {
            out.addStyle(letterRoom(look, 0f), space, space + 1)
            out.addStyle(SpanStyle(letterSpacing = (2 * EditorMetrics.SPACE_EM).em), chip.start, space)
        } else {
            out.addStyle(letterRoom(look, EditorMetrics.SPACE_EM * EditorMetrics.CHIP_SCALE), space, space + 1)
        }
    }

    /** Whether [text] from [from] until [to] has a Hebrew or an Arabic letter, a Persian one among them. */
    private fun hasRightToLeft(
        text: String,
        from: Int,
        to: Int,
    ): Boolean {
        for (i in from until to) {
            val c = text[i]
            if (c in FIRST_RTL..LAST_RTL || c in FIRST_RTL_FORM..LAST_RTL_FORM) return true
        }
        return false
    }

    /**
     * The line as it is drawn where its days must read left to right: the
     * space in each chip is drawn as a Latin letter that is not seen
     * ([letterRoom]). A day after an Arabic letter is a number of that script
     * and its parts are set right to left, `30-10-2026`; after a Latin letter
     * it is `2026-10-30`. The letter is in the drawn text only, at the place
     * of the space, so every place in the note is the place it was.
     *
     * In a line that reads left to right the emoji stays at the day's left.
     * In one that reads right to left the emoji is at the day's right, where
     * that line's reading comes to it first.
     */
    private fun withLetters(
        text: String,
        start: Int,
        length: Int,
        chips: List<EditorChip>,
    ): String {
        val line = StringBuilder(length).append(text, start, start + length)
        for (chip in chips) {
            val space = line.indexOf(" ", chip.start)
            if (space in chip.start until chip.end) line[space] = LETTER
        }
        return line.toString()
    }

    /**
     * A letter that is not seen and is [em] wide: room, in place of a space.
     * It is never larger than the text, which would push the line's baseline
     * down.
     */
    private fun letterRoom(
        look: EditorLook,
        em: Float,
    ): SpanStyle {
        // A monospace letter's width, of its size.
        val advance = EditorMetrics.BOX_EM / look.boxScale
        return SpanStyle(
            color = Color.Transparent,
            fontFamily = FontFamily.Monospace,
            fontSize = (look.size * minOf(1f, em / advance)).sp,
            background = Color.Unspecified,
        )
    }

    /**
     * The two letters that stand where a task's box is drawn: not seen, and
     * as wide as the box together. Two, so each is smaller than the text: a
     * letter larger than the text would push the line's baseline down.
     */
    private fun boxRoom(look: EditorLook) =
        SpanStyle(
            color = Color.Transparent,
            fontFamily = FontFamily.Monospace,
            fontSize = (look.size * look.boxScale / BOX_LETTERS).sp,
            background = Color.Unspecified,
        )

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
            // A task's line reads as its words do: the `x` of a done box is no word.
            textDirection =
                when {
                    line.task == null -> TextDirection.Content
                    line.rtl -> TextDirection.Rtl
                    else -> TextDirection.Ltr
                },
            textIndent = indent,
        )
    }

    private companion object {
        /** Stands for a line's break: it ends the line's paragraph and has no width. */
        const val PARAGRAPH_END = '\u200B'

        /** How many of a box's marks keep its room: `[x]`, the shortest box, has three. */
        const val BOX_LETTERS = 2

        /** Stands for a space where a chip must read left to right: a Latin letter, drawn in no colour. */
        const val LETTER = 'l'

        /** Hebrew to the end of Arabic's blocks, and their presentation forms. */
        const val FIRST_RTL = '\u0590'
        const val LAST_RTL = '\u08FF'
        const val FIRST_RTL_FORM = '\uFB1D'
        const val LAST_RTL_FORM = '\uFEFC'

        /** A mark that is not shown: it is in the text and takes no room. */
        val NO_WIDTH = SpanStyle(fontSize = 0.sp)

        /** The line's height is the whole line's, the first and the last one too. */
        val FULL_LINE = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    }
}
