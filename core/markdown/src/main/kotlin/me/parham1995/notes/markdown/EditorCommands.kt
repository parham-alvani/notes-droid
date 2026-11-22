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
     * there: at the ends of the selection, or just outside it. The selection
     * stays on the same words, so a second press undoes the first.
     */
    fun wrap(
        text: String,
        start: Int,
        end: Int,
        mark: String,
    ): EditorEdit {
        val width = mark.length
        val selected = text.substring(start, end)
        // The marks are in the selection.
        if (selected.length >= 2 * width &&
            selected.startsWith(mark) &&
            selected.endsWith(mark) &&
            wraps(selected, mark)
        ) {
            val inner = selected.substring(width, selected.length - width)
            return EditorEdit(text.replaceRange(start, end, inner), start, start + inner.length)
        }
        // The marks are around the selection.
        if (around(text, start, end, mark)) {
            val without = text.removeRange(end, end + width).removeRange(start - width, start)
            return EditorEdit(without, start - width, end - width)
        }
        return EditorEdit(text.replaceRange(start, end, mark + selected + mark), start + width, end + width)
    }

    /** `**a**` selected whole is bold, and not italic: one star is italic's only when the run is odd. */
    private fun wraps(
        selected: String,
        mark: String,
    ): Boolean {
        if (mark != ITALIC) return true
        val before = selected.takeWhile { it == '*' }.length
        val after = selected.takeLastWhile { it == '*' }.length
        return minOf(before, after) % 2 == 1
    }

    /** Whether [mark] sits on both sides of the selection. Stars are counted: `**a**` is bold and not italic. */
    private fun around(
        text: String,
        start: Int,
        end: Int,
        mark: String,
    ): Boolean {
        val c = mark[0]
        var before = 0
        while (start - before > 0 && text[start - before - 1] == c) before++
        var after = 0
        while (end + after < text.length && text[end + after] == c) after++
        val run = minOf(before, after)
        return when {
            c != '*' -> run >= mark.length
            mark == BOLD -> run >= 2
            else -> run % 2 == 1
        }
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
