package me.parham1995.notes.markdown

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One task as it is actually written in the file.
 *
 * Everything else in the app works with a task as the index stored it -- text
 * with the markup and the trailing emoji taken off -- and none of that can be
 * written back. Ticking a task means editing the line that produced it, so this
 * is the piece that goes the other way: from a raw line to what the index would
 * have made of it, and from a raw line to the same line completed.
 */
object TaskLine {
    /**
     * `- [ ] `, `* [x] `, `1. [/] `, `- [>] ` -- marker, status, and the rest.
     *
     * Any single character is a status, as it is to the Tasks plugin and to
     * the parser: a line the note shows as a task has to be one this can tick.
     */
    private val LINE = Regex("""^(\s*(?:[-*+]|\d+[.)])\s+)\[([^\]\n])]\s*(.*)$""")

    fun isTask(raw: String): Boolean = LINE.matches(raw)

    fun isOpen(raw: String): Boolean =
        LINE
            .find(raw)
            ?.groupValues
            ?.get(2)
            ?.let { isOpenStatus(it) } ?: false

    /** Done is `x`, cancelled is `-`; everything else is still to do. */
    private fun isOpenStatus(status: String): Boolean = status !in CLOSED

    /** Whether completing it would drop a repeat rule the app cannot re-create. */
    fun isRecurring(raw: String): Boolean =
        LINE
            .find(raw)
            ?.groupValues
            ?.get(3)
            ?.contains(RECUR) ?: false

    /**
     * The same line, ticked and dated, or null when it is not an open task or
     * is already done.
     *
     * The date goes on the end because that is where the Tasks plugin's
     * metadata lives and where every other completed line in this vault carries
     * it.
     */
    fun complete(
        raw: String,
        today: String,
    ): String? {
        val match = LINE.find(raw) ?: return null
        val (prefix, state, body) = match.destructured
        if (!isOpenStatus(state)) return null
        if (body.contains(DONE)) return null
        return "$prefix[x] ${body.trimEnd()} $DONE $today"
    }

    /**
     * Completes a repeating task: the next occurrence, then the one just done.
     *
     * Two lines, because that is what completing a repeat means -- the work
     * comes back. The new one goes first, which is where the Tasks plugin puts
     * it, so the open task stays above the record of the finished one.
     *
     * Null when this is not a repeating task, when its rule is not one the app
     * will act on, or when it carries no date to move: a repeat with no dates
     * has no next occurrence to describe, and copying the line unchanged would
     * only duplicate it.
     *
     * Every date on the line moves by the same number of days. The rule decides
     * how far the *leading* date travels -- due, else scheduled, else start --
     * and the others follow by exactly that, so a task scheduled three days
     * before it is due stays scheduled three days before it is due.
     */
    fun completeRecurring(
        raw: String,
        today: String,
    ): List<String>? {
        val match = LINE.find(raw) ?: return null
        val (prefix, state, body) = match.destructured
        if (!isOpenStatus(state)) return null

        val split = TaskMetadata.split(body)
        val rule = split.meta.firstOrNull { it.emoji == RECUR }?.value ?: return null
        val recurrence = TaskRecurrence.parse(rule) ?: return null

        val dates = split.meta.mapNotNull { meta -> meta.date()?.let { meta.emoji to it } }.toMap()
        val leading = MOVED.firstNotNullOfOrNull { dates[it] } ?: return null
        val now = today.toDateOrNull() ?: return null

        val from = if (recurrence.whenDone) now else leading
        val shift = ChronoUnit.DAYS.between(leading, TaskRecurrence.next(from, recurrence, now))

        val moved =
            split.meta.map { meta ->
                val date = dates[meta.emoji]
                if (meta.emoji in MOVED && date != null) meta.copy(value = date.plusDays(shift).toString()) else meta
            }
        val next = prefix + "[ ] " + rebuild(split.text, moved)
        val done = prefix + "[x] " + body.trimEnd() + " " + DONE + " " + today
        return listOf(next, done)
    }

    private fun rebuild(
        text: String,
        meta: List<TaskMeta>,
    ): String =
        (listOf(text.trimEnd()) + meta.map { "${it.emoji} ${it.value}".trimEnd() })
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private fun TaskMeta.date(): LocalDate? = value.toDateOrNull()

    private fun String.toDateOrNull(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

    /**
     * What the index would call this line, so a stored task can be matched back
     * to the line it came from.
     *
     * Parsed rather than pattern-matched: the stored text has had wikilinks
     * reduced to their alias, emphasis unwrapped and the emoji metadata
     * stripped, and re-deriving that with a regular expression would be the
     * renderer written a second time and wrong in a different way.
     */
    fun indexedText(raw: String): String? {
        if (!isTask(raw)) return null
        val note = MarkdownParser.parseNote(raw.trimStart())
        return TaskExtractor.extract(note).firstOrNull()?.text
    }

    private val CLOSED = setOf("x", "X", "-")

    private const val DONE = "✅"
    private const val RECUR = "🔁"

    /** The dates that describe when a task happens, and so travel with it. */
    private val MOVED = listOf("📅", "⏳", "🛫")
}
