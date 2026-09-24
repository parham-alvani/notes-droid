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

    /**
     * Finds the line a stored task came from, or null when it is not there.
     *
     * By recorded line number first, and only then by searching, because two
     * tasks in one file can read identically -- "- [ ] follow up" is in this
     * vault more than once -- and the number is what tells them apart. The
     * number is still checked against what the index made of the line, because
     * a note can grow a paragraph above a task between one sync and the next.
     */
    fun locate(
        lines: List<String>,
        line: Int,
        text: String,
    ): Int? {
        val at = lines.getOrNull(line)
        if (at != null && indexedText(at) == text) return line
        return lines.indexOfFirst { isOpen(it) && indexedText(it) == text }.takeIf { it >= 0 }
    }

    /** A task moved to another day, and what it said before. */
    data class Rescheduled(
        val line: String,
        /** Which date moved: `⏳` or `📅`. */
        val emoji: String,
        /**
         * What the field said before, as written, or null when there was no
         * such field and a scheduled date was added -- which is what tells an
         * undo whether to move the date back or take it off again.
         */
        val previous: String?,
    )

    /**
     * Moves the date this task is next looked at to [date].
     *
     * Scheduled if the line has one, else due, else a scheduled date is added.
     * Scheduled first because it is the date that means "not before" -- this
     * vault schedules work rather than promising it -- and moving a due date is
     * moving a promise, done only when there is nothing else to move.
     *
     * The date is changed where it stands. Nothing else on the line moves: not
     * the created date, not a repeat rule, not a block id, whose place at the
     * very end is what makes it a block id. An added date goes after the other
     * metadata and before any block id, for the same reason.
     *
     * Null when the line already carries [date] there, or is not a task.
     */
    fun reschedule(
        raw: String,
        date: String,
    ): Rescheduled? {
        val parts = parts(raw) ?: return null
        val target = listOf(SCHEDULED, DUE).firstNotNullOfOrNull { emoji -> parts.find(emoji) }
        if (target == null) {
            return Rescheduled(parts.head + parts.rest + " " + SCHEDULED + " " + date + parts.tail, SCHEDULED, null)
        }
        val (emoji, range) = target
        val previous = parts.rest.substring(range.first, range.last + 1)
        if (previous == date) return null
        val rest = parts.rest.replaceRange(range.first, range.last + 1, if (range.isEmpty()) " $date" else date)
        return Rescheduled(parts.head + rest + parts.tail, emoji, previous)
    }

    /**
     * Takes the scheduled date off the line, or null when it has none.
     *
     * The inverse of the one case of [reschedule] that adds rather than moves:
     * the added date went in as ` ⏳ date` at the end of the metadata, and this
     * takes exactly that back out.
     */
    fun unschedule(raw: String): String? {
        val parts = parts(raw) ?: return null
        val (_, range) = parts.find(SCHEDULED) ?: return null
        val symbol = parts.rest.lastIndexOf(SCHEDULED, range.first)
        var start = symbol
        while (start > 0 && parts.rest[start - 1].isWhitespace()) start--
        val rest = parts.rest.removeRange(start, range.last + 1)
        return parts.head + rest + parts.tail
    }

    /**
     * A task line cut in three: everything up to the text, the text and its
     * metadata, and whatever follows the metadata -- a block id, trailing
     * space -- which no edit to a date should ever touch.
     */
    private class Parts(
        val head: String,
        val rest: String,
        val tail: String,
    ) {
        private val metadata = TaskMetadata.split(rest)

        /**
         * Where the value of the [emoji] field sits in [rest], or null when the
         * line has no such field. The metadata is a suffix, so the search
         * starts where the sentence ends: an emoji in the prose is prose.
         */
        fun find(emoji: String): Pair<String, IntRange>? {
            if (metadata.meta.none { it.emoji == emoji }) return null
            var cursor = metadata.text.length
            metadata.meta.forEach { meta ->
                val at = rest.indexOf(meta.emoji, cursor)
                if (at < 0) return null
                cursor = at + meta.emoji.length
                if (meta.emoji == emoji) {
                    // The value is what follows the symbol, trimmed; an empty
                    // one is an empty range just after the symbol.
                    var begin = cursor
                    while (meta.value.isNotEmpty() && begin < rest.length && rest[begin].isWhitespace()) begin++
                    return emoji to (begin until begin + meta.value.length)
                }
            }
            return null
        }
    }

    private fun parts(raw: String): Parts? {
        val match = LINE.find(raw) ?: return null
        val body = match.groups[3] ?: return null
        val start = body.range.first
        val tail = BLOCK_TAIL.find(body.value)?.value.orEmpty()
        val rest = body.value.substring(0, body.value.length - tail.length)
        return Parts(raw.substring(0, start), rest, tail)
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
    private const val SCHEDULED = "⏳"
    private const val DUE = "📅"

    /** A trailing block id and whatever space surrounds it. */
    private val BLOCK_TAIL = Regex("""(\s+\^[A-Za-z0-9-]+)?\s*$""")

    /** The dates that describe when a task happens, and so travel with it. */
    private val MOVED = listOf("📅", "⏳", "🛫")
}
