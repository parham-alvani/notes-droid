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

    fun isOpen(
        raw: String,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): Boolean =
        LINE
            .find(raw)
            ?.groupValues
            ?.get(2)
            ?.let { statuses.isOpen(it.single()) } ?: false

    /** The character between the brackets, or null when [raw] is not a task. */
    fun statusOf(raw: String): Char? =
        LINE
            .find(raw)
            ?.groupValues
            ?.get(2)
            ?.single()

    /** The task's reason ([TaskReason]), or null when it gives none. */
    fun reasonOf(raw: String): String? {
        val parts = parts(raw) ?: return null
        return TaskReason.split(TaskMetadata.split(parts.rest).text).reason
    }

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
     * it. A reason goes: it belonged to the status the task had.
     */
    fun complete(
        raw: String,
        today: String,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): String? {
        val match = LINE.find(raw) ?: return null
        val (prefix, state, whole) = match.destructured
        if (!statuses.isOpen(state.single())) return null
        if (whole.contains(DONE)) return null
        // A block id stays the line's last word: that is what makes it one.
        val (body, id) = splitBlockId(whole)
        return "$prefix[x] ${reasonSetIn(body, null).trimEnd()} $DONE $today$id"
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
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): List<String>? {
        val match = LINE.find(raw) ?: return null
        val (prefix, state, whole) = match.destructured
        if (!statuses.isOpen(state.single())) return null
        // The block id names the line that was there, which is the one being
        // finished; the next occurrence is a new line and gets none, since
        // two lines cannot answer to one id. Neither keeps a reason: the next
        // one has not been given any, and the finished one changed status.
        val (written, id) = splitBlockId(whole)
        val body = reasonSetIn(written, null)

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
        val done = prefix + "[x] " + body.trimEnd() + " " + DONE + " " + today + id
        return listOf(next, done)
    }

    /**
     * The line with its status set to [status], as the board writes a task
     * dropped on another column or a picker sets it; null when nothing would
     * change or it is not a task.
     *
     * The closing dates follow the type of the status, not its character, so
     * `[r]` in a vault that calls it "In Review" is under way like `[/]`. To
     * a done-typed status from an open one is the tick, exactly as a tap
     * makes it -- [complete], or [completeRecurring] for a repeat, which is
     * null here when the rule is not one the app works out (the caller says
     * so rather than dropping the repeat) -- with [status] put in for `x`
     * afterwards. Otherwise the status changes, and `✅` is kept when the task
     * was already done-typed, dated [today] when it becomes so, and taken off
     * when it does not stay so; `❌` likewise for cancelled. A block id stays
     * last.
     *
     * A reason belongs to the status it was given with, so any change of
     * status takes the old one off, and puts [reason] on when one is given.
     * Asked for the status the task already has, only a given [reason]
     * changes anything.
     */
    fun withStatus(
        raw: String,
        status: Char,
        today: String,
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
        reason: String? = null,
    ): List<String>? {
        val match = LINE.find(raw) ?: return null
        val current = match.groupValues[2].single()
        // `[X]` is done as `[x]` is; written by hand, it is not rewritten.
        if (current == status ||
            (status == 'x' && current == 'X')
        ) {
            return reason?.let { withReason(raw, it) }?.let(::listOf)
        }
        val was = statuses.of(current).type
        val becomes = statuses.of(status).type
        val at = match.groups[2]!!.range.first
        if (becomes == StatusType.DONE && !was.isClosed) {
            val ticked =
                (
                    if (isRecurring(
                            raw,
                        )
                    ) {
                        completeRecurring(raw, today, statuses)
                    } else {
                        complete(raw, today, statuses)?.let(::listOf)
                    }
                )
                    ?: return null
            // The finished line is the last; the tick wrote `[x]` where the
            // status was, so the same place takes [status].
            val done = ticked.last().replaceRange(at, at + 1, status.toString())
            return ticked.dropLast(1) + (reason?.let { withReason(done, it) } ?: done)
        }
        val parts = parts(raw) ?: return null
        val head = parts.head.replaceRange(at, at + 1, status.toString())
        var rest = reasonSetIn(parts.rest, reason)
        for ((emoji, type) in listOf(DONE to StatusType.DONE, CANCELLED to StatusType.CANCELLED)) {
            if (was == type && becomes == type) continue
            rest = withoutField(rest, emoji) ?: rest
            if (becomes == type) rest = rest.trimEnd() + " " + emoji + " " + today
        }
        return listOf(head + rest + parts.tail)
    }

    /**
     * The line with its reason set to [reason] -- written as
     * [TaskReason.field] says, one space after the text and before the
     * emoji -- or taken off when [reason] is blank; null when that is what it
     * already says, or it is not a task. The status and every date stay.
     */
    fun withReason(
        raw: String,
        reason: String?,
    ): String? {
        val parts = parts(raw) ?: return null
        val line = parts.head + reasonSetIn(parts.rest, reason) + parts.tail
        return line.takeIf { it != raw }
    }

    /**
     * [rest] -- a task's text and metadata, no block id -- with its reason
     * replaced by [reason], or taken off when that is null or blank.
     */
    private fun reasonSetIn(
        rest: String,
        reason: String?,
    ): String {
        val text = TaskMetadata.split(rest).text
        val signifiers = rest.substring(text.length)
        val split = TaskReason.split(text)
        val field = TaskReason.field(reason)
        // A line with no field and none to add is left exactly as written.
        if (split.range == null && field == null) return rest
        val without =
            split.range?.let { TaskReason.fold(text.substring(0, it.first), text.substring(it.last + 1)) }
                ?: text.trimEnd()
        val body = if (field == null) without else listOf(without, field).filter { it.isNotEmpty() }.joinToString(" ")
        return body + signifiers
    }

    /** The task's text and metadata, and its trailing ` ^block-id` or nothing. */
    private fun splitBlockId(body: String): Pair<String, String> {
        val tail = BLOCK_TAIL.find(body)?.value.orEmpty()
        return body.substring(0, body.length - tail.length) to tail.trimEnd()
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
        statuses: TaskStatuses = TaskStatuses.DEFAULT,
    ): Int? {
        val at = lines.getOrNull(line)
        if (at != null && indexedText(at) == text) return line
        return lines.indexOfFirst { isOpen(it, statuses) && indexedText(it) == text }.takeIf { it >= 0 }
    }

    /** A task moved to another day, and what it said before. */
    data class Rescheduled(
        val line: String,
        /** Which date led the move: `📅` or `⏳`. */
        val emoji: String,
        /**
         * What the leading field said before, as written, or null when there
         * was no such field and a scheduled date was added -- which is what
         * tells an undo whether to move the dates back or take it off again.
         * Moving the leading date back to this moves the others back by the
         * same number of days, so that undo is exact.
         */
        val previous: String?,
    )

    /**
     * Moves the task to [date], so that the day it is grouped under is [date].
     *
     * The date that moves to [date] is the one the index files the task by --
     * due, else scheduled ([ParsedTask.actionableOn]) -- because moving any
     * other would leave the task in the group it was just taken out of. The
     * task's other dates (due, scheduled, start) follow by the same number of
     * days, exactly as a repeat moves them, so a task scheduled three days
     * before it is due stays three days before it. Created and done dates are
     * history and stay put. With neither due nor scheduled on the line, a
     * scheduled date is added.
     *
     * Each date is changed where it stands. Nothing else on the line moves:
     * not a repeat rule, not a block id, whose place at the very end is what
     * makes it a block id. An added date goes after the other metadata and
     * before any block id, for the same reason.
     *
     * Null when the line is already filed under [date], or is not a task.
     */
    fun reschedule(
        raw: String,
        date: String,
    ): Rescheduled? {
        val parts = parts(raw) ?: return null
        val target = date.toDateOrNull() ?: return null
        val lead = FILED_BY.firstNotNullOfOrNull { emoji -> parts.find(emoji) }
        if (lead == null) {
            return Rescheduled(parts.head + parts.rest + " " + SCHEDULED + " " + date + parts.tail, SCHEDULED, null)
        }
        val (emoji, range) = lead
        val previous = parts.rest.substring(range.first, range.last + 1)
        if (previous == date) return null
        val from = previous.toDateOrNull()
        // A leading field that is not a date gives no distance to move the
        // others by, so it alone is set and they are left where they are.
        val shift = from?.let { ChronoUnit.DAYS.between(it, target) }
        val edits =
            MOVED
                .mapNotNull { other -> parts.find(other) }
                .mapNotNull { (other, at) ->
                    val value = parts.rest.substring(at.first, at.last + 1)
                    when {
                        other == emoji -> at to (if (at.isEmpty()) " $date" else date)
                        shift == null -> null
                        else -> value.toDateOrNull()?.let { at to it.plusDays(shift).toString() }
                    }
                }.sortedByDescending { (at, _) -> at.first }
        // Right to left, so each replacement leaves the earlier ranges valid.
        val rest = edits.fold(parts.rest) { text, (at, value) -> text.replaceRange(at.first, at.last + 1, value) }
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
        val rest = withoutField(parts.rest, SCHEDULED) ?: return null
        return parts.head + rest + parts.tail
    }

    /**
     * Takes every date the task is filed by off the line -- due and
     * scheduled -- so that it has no day at all; null when it has neither.
     *
     * What the board's No Date column writes. [unschedule] is not enough
     * there: a task with a due date keeps its day without its scheduled one.
     * The start date and the history (created, done) stay.
     */
    fun undate(raw: String): String? {
        val parts = parts(raw) ?: return null
        var rest = parts.rest
        FILED_BY.forEach { emoji -> rest = withoutField(rest, emoji) ?: rest }
        if (rest == parts.rest) return null
        return parts.head + rest + parts.tail
    }

    /**
     * [rest] without its [emoji] field, the space before it and its value,
     * or null when it has no such field.
     */
    private fun withoutField(
        rest: String,
        emoji: String,
    ): String? {
        val (_, range) = Parts("", rest, "").find(emoji) ?: return null
        val symbol = rest.lastIndexOf(emoji, range.first)
        var start = symbol
        while (start > 0 && rest[start - 1].isWhitespace()) start--
        return rest.removeRange(start, maxOf(range.last + 1, symbol + emoji.length))
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

    private const val DONE = "✅"
    private const val CANCELLED = "❌"
    private const val RECUR = "🔁"
    private const val SCHEDULED = "⏳"
    private const val DUE = "📅"

    /** The dates the index files a task by, in the order it prefers them. */
    private val FILED_BY = listOf(DUE, SCHEDULED)

    /** A trailing block id and whatever space surrounds it. */
    private val BLOCK_TAIL = Regex("""(\s+\^[A-Za-z0-9-]+)?\s*$""")

    /** The dates that describe when a task happens, and so travel with it. */
    private val MOVED = listOf("📅", "⏳", "🛫")
}
