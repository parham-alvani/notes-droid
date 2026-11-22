package me.parham1995.notes.markdown

/** A note's text after a command, with the selection from [start] to [end]. */
data class EditorEdit(
    val text: String,
    val start: Int,
    val end: Int,
)

/**
 * What the editor's buttons do to a note's text (`look.md` in notes-linux's
 * `docs/specs/`, "The format bar" and "Editor: the task box"). Each is a
 * function of the text and the selection, so it is one change and one undo
 * step.
 */
object EditorCommands {
    const val BOLD = "**"
    const val ITALIC = "*"
    const val HIGHLIGHT = "=="
    const val CODE = "`"
    const val STRIKE = "~~"

    private val QUOTE_PREFIX = Regex("""^ {0,3}>(?: ?>)* ?""")
    private const val LINK_OPEN = "[["
    private const val LINK_CLOSE = "]]"

    /**
     * Wraps the selection in [mark], or takes the marks off when they are
     * there: at the ends of the selection, just outside it, or partly both
     * (a selection that starts in the middle of `**`). The selection stays on
     * the same words, so a second press undoes the first. Only the mark's own
     * characters are ever taken out.
     *
     * Stars are counted as one run on each side, inside and outside the
     * selection together: an odd run holds italic's star, a run of two or
     * more holds bold's, so `**a**` is bold and not italic however much of
     * its stars the selection holds.
     */
    fun wrap(
        text: String,
        start: Int,
        end: Int,
        mark: String,
    ): EditorEdit {
        val width = mark.length
        val c = mark[0]
        // The run of the mark's character on each side: outside the selection, and inside it.
        var outsideBefore = 0
        while (start - outsideBefore > 0 && text[start - outsideBefore - 1] == c) outsideBefore++
        var insideBefore = 0
        while (start + insideBefore < end && text[start + insideBefore] == c) insideBefore++
        var outsideAfter = 0
        while (end + outsideAfter < text.length && text[end + outsideAfter] == c) outsideAfter++
        var insideAfter = 0
        while (end - insideAfter > start && text[end - insideAfter - 1] == c) insideAfter++

        val run = minOf(outsideBefore + insideBefore, outsideAfter + insideAfter)
        val marked =
            when {
                // Nothing but marks is selected: there are no words to take them off.
                insideBefore == end - start -> false
                c != '*' -> run >= width
                mark == BOLD -> run >= 2
                else -> run % 2 == 1
            }
        if (!marked) {
            return EditorEdit(
                text.replaceRange(start, end, mark + text.substring(start, end) + mark),
                start + width,
                end + width,
            )
        }
        // The marks next to the selection's ends go: outside it first, then inside.
        val before = minOf(outsideBefore, width)
        val after = minOf(outsideAfter, width)
        val without =
            text
                .removeRange(end - (width - after), end + after)
                .removeRange(start - before, start - before + width)
        return EditorEdit(without, start - before, end - width - (width - after))
    }

    /** A link to a note: `[[selection]]`, with the selection on the name. Around a link already, the brackets come off. */
    fun link(
        text: String,
        start: Int,
        end: Int,
    ): EditorEdit {
        if (text.startsWith(LINK_OPEN, start - LINK_OPEN.length) && text.startsWith(LINK_CLOSE, end)) {
            val without = text.removeRange(end, end + LINK_CLOSE.length).removeRange(start - LINK_OPEN.length, start)
            return EditorEdit(without, start - LINK_OPEN.length, end - LINK_OPEN.length)
        }
        val linked = LINK_OPEN + text.substring(start, end) + LINK_CLOSE
        return EditorEdit(text.replaceRange(start, end, linked), start + LINK_OPEN.length, end + LINK_OPEN.length)
    }

    /**
     * A tap on the box of the task on the line that holds [at]: the status
     * changes as [TaskLine.toggled] says. Null when the line is no task.
     *
     * The selection ([start] to [end]) stays on the same text: a place in the
     * task's line keeps its offset in that line, and a place after it moves
     * with the text, past a repeat's next occurrence too.
     */
    fun toggleTask(
        text: String,
        at: Int,
        start: Int,
        end: Int,
        today: String,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): EditorEdit? = onTask(text, at, start, end) { TaskLine.toggled(it, today, statuses) }

    /** The task on the line that holds [at] with its status set to [status], as [TaskLine.withStatus] says. */
    fun setStatus(
        text: String,
        at: Int,
        start: Int,
        end: Int,
        status: Char,
        today: String,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): EditorEdit? = onTask(text, at, start, end) { TaskLine.withStatus(it, status, today, statuses) }

    /** The status of the task on the line that holds [at], or null when it is no task. */
    fun statusAt(
        text: String,
        at: Int,
    ): Char? {
        val from = text.lastIndexOf('\n', at - 1) + 1
        val to = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val line = text.substring(from, to)
        return TaskLine.statusOf(line.substring(QUOTE_PREFIX.find(line)?.value?.length ?: 0))
    }

    private fun onTask(
        text: String,
        at: Int,
        start: Int,
        end: Int,
        change: (String) -> List<String>?,
    ): EditorEdit? {
        val from = text.lastIndexOf('\n', at - 1) + 1
        val to = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val line = text.substring(from, to)
        // A task in a quote or a callout: the `>` marks stay on every line.
        val prefix = QUOTE_PREFIX.find(line)?.value.orEmpty()
        val lines = change(line.substring(prefix.length))?.map { prefix + it } ?: return null
        val written = lines.joinToString("\n")
        // The task is the last line; a repeat's next occurrence is above it.
        val task = from + written.length - lines.last().length

        fun moved(place: Int): Int =
            when {
                place < from -> place
                place > to -> place + written.length - line.length
                else -> task + minOf(place - from, lines.last().length)
            }
        return EditorEdit(text.replaceRange(from, to, written), moved(start), moved(end))
    }
}
