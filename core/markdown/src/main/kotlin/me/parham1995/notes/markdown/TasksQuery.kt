package me.parham1995.notes.markdown

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * One task as a ` ```tasks ` query sees it.
 *
 * Only what the index keeps. A start date and a priority are not stored, so
 * the instructions that need them are reported as not understood rather than
 * answered from nothing.
 */
data class QueryTask(
    val text: String,
    val state: TaskState,
    /** The note's path inside its vault, `Folder/Note.md`. */
    val path: String,
    /** The heading above the task. */
    val section: String,
    val due: String? = null,
    val scheduled: String? = null,
    val done: String? = null,
    val recurring: String? = null,
)

/**
 * The Obsidian Tasks plugin's query language, the part of it a reader can
 * answer from the index: filters on status, dates, path, heading, text and
 * tags, joined with `AND`/`OR`/`NOT` when wanted, then `sort by` and `limit`.
 *
 * What it cannot answer is kept in [notUnderstood] and shown. Dropping a filter
 * silently would list tasks the query was written to leave out, and the reader
 * would have no way to tell.
 */
class TasksQuery private constructor(
    private val filters: List<Filter>,
    private val sorts: List<Sort>,
    private val groupers: List<(QueryTask) -> String>,
    val limit: Int?,
    /** The lines this could not read, as written. */
    val notUnderstood: List<String>,
) {
    /**
     * The tasks the query selects, in the order it asks for.
     *
     * Generic so a caller keeps its own rows -- the ones that can be ticked --
     * and only says how to see each as a [QueryTask]. With no `sort by`, open
     * tasks come before finished ones and sooner before later, which is what
     * the plugin does by default; ties keep the order they were given in.
     */
    fun <T> run(
        tasks: List<T>,
        today: LocalDate,
        view: (T) -> QueryTask,
    ): List<T> {
        val kept = tasks.filter { row -> view(row).let { task -> filters.all { it.test(task, today) } } }
        // Groups first, in the order of their names, as the plugin lists them;
        // the sort then orders the tasks inside each.
        val byGroup = groupers.map { group -> compareBy<QueryTask> { group(it) } }
        val order =
            (byGroup + sorts.map { it.comparator() } + DEFAULT_ORDER)
                .reduce { a, b -> a.thenComparing(b) }
        val sorted = kept.sortedWith(compareBy(order) { view(it) })
        return limit?.let { sorted.take(it) } ?: sorted
    }

    /**
     * The groups [task] is listed under, outermost first, one per `group by`
     * line. Empty for a query with none; a group may be named "" -- a task
     * with no heading, grouped by heading -- and is drawn without a title.
     */
    fun groupsOf(task: QueryTask): List<String> = groupers.map { it(task) }

    companion object {
        /**
         * Reads [source], the body of the fence.
         *
         * [file] is the path of the note the query is written in, which the
         * `{{query.file.*}}` placeholders stand for. Without it a line that
         * uses one is not understood.
         */
        fun parse(
            source: String,
            file: String? = null,
        ): TasksQuery {
            val filters = mutableListOf<Filter>()
            val sorts = mutableListOf<Sort>()
            val groupers = mutableListOf<(QueryTask) -> String>()
            var limit: Int? = null
            val notUnderstood = mutableListOf<String>()

            source.lines().forEach { raw ->
                val line = raw.trim()
                // A blank line and a comment say nothing; a layout line says
                // how to draw the list, not which tasks are in it.
                if (line.isEmpty() ||
                    line.startsWith("#") ||
                    LAYOUT.any { line.lowercase(Locale.ROOT).startsWith(it) }
                ) {
                    return@forEach
                }
                val expanded = placeholders(line, file)
                val lower = expanded?.lowercase(Locale.ROOT)
                when {
                    lower == null -> notUnderstood += line
                    lower.startsWith("group by ") ->
                        group(expanded.substring("group by ".length))?.let { groupers += it }
                            ?: run { notUnderstood += line }
                    lower.startsWith("filter by function ") ->
                        TaskExpression
                            .parse(expanded.substring("filter by function ".length))
                            ?.let { expression -> filters += Filter { t, _ -> expression.test(t) } }
                            ?: run { notUnderstood += line }
                    LIMIT.matches(lower) -> limit = LIMIT.find(lower)!!.groupValues[1].toInt()
                    lower.startsWith("sort by ") ->
                        sort(lower.removePrefix("sort by "))?.let { sorts += it }
                            ?: run { notUnderstood += line }
                    else -> expression(expanded)?.let { filters += it } ?: run { notUnderstood += line }
                }
            }
            return TasksQuery(filters, sorts, groupers, limit, notUnderstood)
        }

        /**
         * A `group by` line: one of the plugin's named groups, or a function
         * read by [TaskExpression].
         */
        private fun group(spec: String): ((QueryTask) -> String)? {
            val source =
                if (spec.lowercase(Locale.ROOT).startsWith("function ")) {
                    spec.substring("function ".length)
                } else {
                    NAMED_GROUPS[spec.trim().lowercase(Locale.ROOT)] ?: return null
                }
            val expression = TaskExpression.parse(source) ?: return null
            return { task ->
                when (val value = expression.evaluate(task)) {
                    null -> ""
                    is List<*> -> value.joinToString(", ")
                    else -> value.toString()
                }
            }
        }

        /** The plugin's named groups, said as the function each one is. */
        private val NAMED_GROUPS =
            mapOf(
                "filename" to "task.file.filenameWithoutExtension",
                "path" to "task.file.path",
                "folder" to "task.file.folder",
                "heading" to "task.heading",
                "status" to "task.status.name",
                "status.name" to "task.status.name",
                "status.type" to "task.status.type",
            )

        /** Fills in `{{query.file.*}}`, or null when one cannot be filled. */
        private fun placeholders(
            line: String,
            file: String?,
        ): String? {
            if (!line.contains("{{")) return line
            if (file == null) return null
            val name = file.substringAfterLast('/')
            val values =
                mapOf(
                    "query.file.path" to file,
                    "query.file.folder" to file.folder(),
                    "query.file.filename" to name,
                    "query.file.filenameWithoutExtension" to name.removeSuffix(".md"),
                )
            var unknown = false
            val out =
                PLACEHOLDER.replace(line) { match ->
                    values[match.groupValues[1].trim()] ?: run {
                        unknown = true
                        match.value
                    }
                }
            return out.takeUnless { unknown }
        }

        /**
         * A filter line, which may be a boolean combination:
         * `(due today) OR (scheduled today)`, `NOT (done)`.
         */
        private fun expression(line: String): Filter? {
            val text = line.trim()
            if (!text.startsWith("(") && NEGATION.matchAt(text, 0) == null) return filter(text)
            return Combination(text).parse()
        }

        /** One plain filter, without any boolean around it. */
        internal fun filter(line: String): Filter? {
            // Matched on the line as written, ignoring case, so a value -- a
            // Persian heading, a path -- reaches the filter unchanged.
            val text = line.trim()

            fun Regex.groups() = matchEntire(text)?.groupValues?.map { it.trim() }
            return when (text.lowercase(Locale.ROOT)) {
                // The plugin's "done" is anything no longer to be done:
                // ticked or cancelled. "not done" is the rest.
                "done" -> Filter { t, _ -> t.state.isFinished }
                "not done" -> Filter { t, _ -> !t.state.isFinished }
                "is recurring" -> Filter { t, _ -> t.recurring != null }
                "is not recurring" -> Filter { t, _ -> t.recurring == null }
                else ->
                    STATUS_TYPE.groups()?.let(::statusType)
                        ?: HAS_DATE.groups()?.let(::hasDate)
                        ?: DATE.groups()?.let(::date)
                        ?: TEXT.groups()?.let(::textFilter)
                        ?: TAG.groups()?.let(::tag)
            }
        }

        private fun statusType(groups: List<String>): Filter? {
            val wanted =
                when (groups[2].lowercase(Locale.ROOT).replace(' ', '_')) {
                    "todo" -> setOf(TaskState.UNCHECKED)
                    "in_progress" -> setOf(TaskState.IN_PROGRESS)
                    "done" -> setOf(TaskState.CHECKED)
                    "cancelled" -> setOf(TaskState.CANCELLED)
                    else -> return null
                }
            val negate = groups[1].isNotBlank()
            return Filter { t, _ -> (t.state in wanted) != negate }
        }

        private fun hasDate(groups: List<String>): Filter? {
            val field = dateField(groups[2]) ?: return null
            val wantsOne = groups[1].equals("has", ignoreCase = true)
            return Filter { t, _ -> (field(t) != null) == wantsOne }
        }

        private fun date(groups: List<String>): Filter? {
            val field = dateField(groups[1]) ?: return null
            val relation = groups[2].lowercase(Locale.ROOT).ifEmpty { "on" }
            // Checked once here, so a date the parser cannot read is a line it
            // did not understand rather than a filter that matches nothing.
            if (DateRange.of(groups[3], LocalDate.of(2000, 1, 1)) == null) return null
            return Filter { t, today ->
                val range = DateRange.of(groups[3], today)!!
                field(t)
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?.let { range.relation(relation, it) }
                    ?: false
            }
        }

        private fun dateField(name: String): ((QueryTask) -> String?)? =
            when (name.lowercase(Locale.ROOT)) {
                "due" -> { t -> t.due }
                "scheduled" -> { t -> t.scheduled }
                "done" -> { t -> t.done }
                // The plugin counts the start date too; the index does not keep it.
                "happens" -> { t -> listOfNotNull(t.scheduled, t.due).minOrNull() }
                else -> null
            }

        private fun textFilter(groups: List<String>): Filter? {
            val field: (QueryTask) -> String =
                when (groups[1].lowercase(Locale.ROOT)) {
                    "description" -> { t -> t.text }
                    "path" -> { t -> t.path }
                    "heading" -> { t -> t.section }
                    "filename" -> { t -> t.path.substringAfterLast('/') }
                    "folder" -> { t -> t.path.folder() }
                    else -> return null
                }
            val negate = groups[2].lowercase(Locale.ROOT).startsWith("does not")
            val value = groups[3]
            return Filter { t, _ -> field(t).contains(value, ignoreCase = true) != negate }
        }

        private fun tag(groups: List<String>): Filter {
            val negate = groups[2].lowercase(Locale.ROOT).contains("not")
            val wanted = groups[3].removePrefix("#")
            return Filter { t, _ ->
                val has = tagsOf(t.text).any { it.contains(wanted, ignoreCase = true) }
                has != negate
            }
        }

        private fun sort(spec: String): Sort? {
            val parts = spec.trim().split(Regex("\\s+"))
            val reverse = parts.size == 2 && parts[1] == "reverse"
            if (parts.size > 2 || (parts.size == 2 && !reverse)) return null
            val key: Comparator<QueryTask> =
                when (parts[0]) {
                    "due" -> dates { it.due }
                    "scheduled" -> dates { it.scheduled }
                    "done" -> dates { it.done }
                    "happens" -> dates { listOfNotNull(it.scheduled, it.due).minOrNull() }
                    "status" -> compareBy { it.state.isFinished }
                    "path" -> compareBy { it.path.lowercase(Locale.ROOT) }
                    "filename" -> compareBy { it.path.substringAfterLast('/').lowercase(Locale.ROOT) }
                    "heading" -> compareBy { it.section.lowercase(Locale.ROOT) }
                    "description" -> compareBy { it.text.lowercase(Locale.ROOT) }
                    else -> return null
                }
            return Sort(key, reverse)
        }

        /** Dated first, soonest first, as the plugin sorts a date. */
        private fun dates(of: (QueryTask) -> String?): Comparator<QueryTask> =
            compareBy<QueryTask> { of(it) == null }.thenBy { of(it) }

        private val DEFAULT_ORDER: Comparator<QueryTask> =
            compareBy<QueryTask> { it.state.isFinished }
                .thenComparing(dates { it.due ?: it.scheduled })

        private val TAG_IN_TEXT = Regex("""(?<![\p{L}\p{N}_])#([\p{L}\p{N}_/-]*[\p{L}_/-][\p{L}\p{N}_/-]*)""")

        private fun tagsOf(text: String): List<String> = TAG_IN_TEXT.findAll(text).map { it.groupValues[1] }.toList()

        /** `NOT (`, the operator. Bare "not" begins a filter: `not done`. */
        private val NEGATION = Regex("""NOT\s*\(""", RegexOption.IGNORE_CASE)

        // Every brace escaped: the JVM takes a bare `}` as a literal, Android's
        // ICU engine refuses the pattern, and the note with the query in it
        // crashed the app on the phone while every test passed.
        private val PLACEHOLDER = Regex("""\{\{([^\}]*)\}\}""")
        private val LIMIT = Regex("""^limit (?:to )?(\d+)(?: tasks?)?$""")
        private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)
        private val STATUS_TYPE = Regex("""status\.type is( not)? (todo|in[_ ]progress|done|cancelled)""", IGNORE_CASE)
        private val HAS_DATE = Regex("""(has|no) (due|scheduled|done|happens) date""", IGNORE_CASE)
        private val DATE =
            Regex(
                """(due|scheduled|done|happens)( on or before| on or after| before| after| on| in)?\s+(.+)""",
                IGNORE_CASE,
            )
        private val TEXT =
            Regex("""(description|path|heading|filename|folder) (includes|does not include)\s+(.+)""", IGNORE_CASE)
        private val TAG = Regex("""(tag|tags) (include|includes|do not include|does not include)\s+(.+)""", IGNORE_CASE)

        /**
         * Lines about how the list is drawn. Accepted and ignored: they change
         * nothing about which tasks are listed.
         */
        private val LAYOUT =
            listOf("hide ", "show ", "short mode", "full mode", "explain", "ignore global query")
    }

    fun interface Filter {
        fun test(
            task: QueryTask,
            today: LocalDate,
        ): Boolean
    }

    private class Sort(
        val key: Comparator<QueryTask>,
        val reverse: Boolean,
    ) {
        fun comparator(): Comparator<QueryTask> = if (reverse) key.reversed() else key
    }

    /**
     * `(a) AND (b)`, `(a) OR NOT (b)`, nested to any depth. Operators bind
     * left to right, as written; a query that wants otherwise uses brackets.
     */
    private class Combination(
        private val text: String,
    ) {
        private var at = 0

        fun parse(): Filter? {
            val result = sequence() ?: return null
            skipSpace()
            return result.takeIf { at == text.length }
        }

        private fun sequence(): Filter? {
            var left = operand() ?: return null
            while (true) {
                skipSpace()
                val op = OPERATORS.firstOrNull { text.startsWith(it, at, ignoreCase = true) } ?: return left
                at += op.length
                val right = operand() ?: return null
                val l = left
                left =
                    when (op.trim().uppercase(Locale.ROOT)) {
                        "AND" -> Filter { t, d -> l.test(t, d) && right.test(t, d) }
                        "OR" -> Filter { t, d -> l.test(t, d) || right.test(t, d) }
                        "XOR" -> Filter { t, d -> l.test(t, d) != right.test(t, d) }
                        else -> Filter { t, d -> l.test(t, d) && !right.test(t, d) }
                    }
            }
        }

        private fun operand(): Filter? {
            skipSpace()
            NEGATION.matchAt(text, at)?.let { match ->
                at += match.value.length - 1
                val inner = operand() ?: return null
                return Filter { t, d -> !inner.test(t, d) }
            }
            if (at >= text.length || text[at] != '(') return null
            val close = closing(at) ?: return null
            val inside = text.substring(at + 1, close).trim()
            at = close + 1
            // `NOT (` only: "not done" is a filter, not an operator.
            return if (inside.startsWith("(") || NEGATION.matchAt(inside, 0) != null) {
                Combination(inside).parse()
            } else {
                filter(inside)
            }
        }

        private fun closing(open: Int): Int? {
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) return i
                }
            }
            return null
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private companion object {
            // `AND NOT` before `AND`, so the longer one is taken whole.
            val OPERATORS = listOf("AND NOT ", "AND ", "OR ", "XOR ")
        }
    }
}

/**
 * The days a date phrase in a query means: `today`, `2026-10-09`, `this week`,
 * `next month`, `in 3 days`, or two dates for a range.
 */
internal data class DateRange(
    val start: LocalDate,
    val end: LocalDate,
) {
    fun relation(
        relation: String,
        date: LocalDate,
    ): Boolean? =
        when (relation) {
            "before" -> date.isBefore(start)
            "after" -> date.isAfter(end)
            "on", "in" -> !date.isBefore(start) && !date.isAfter(end)
            "on or before" -> !date.isAfter(end)
            "on or after" -> !date.isBefore(start)
            else -> null
        }

    companion object {
        fun of(
            phrase: String,
            today: LocalDate,
        ): DateRange? {
            val text = phrase.trim().lowercase(Locale.ROOT)

            fun day(d: LocalDate) = DateRange(d, d)
            val monday = today.with(DayOfWeek.MONDAY)
            val firstOfMonth = today.withDayOfMonth(1)
            return when {
                text == "today" -> day(today)
                text == "tomorrow" -> day(today.plusDays(1))
                text == "yesterday" -> day(today.minusDays(1))
                text == "this week" -> DateRange(monday, monday.plusDays(6))
                text == "next week" -> DateRange(monday.plusWeeks(1), monday.plusWeeks(1).plusDays(6))
                text == "last week" -> DateRange(monday.minusWeeks(1), monday.minusWeeks(1).plusDays(6))
                text == "this month" -> DateRange(firstOfMonth, firstOfMonth.plusMonths(1).minusDays(1))
                text == "next month" -> firstOfMonth.plusMonths(1).let { DateRange(it, it.plusMonths(1).minusDays(1)) }
                text == "last month" -> firstOfMonth.minusMonths(1).let { DateRange(it, it.plusMonths(1).minusDays(1)) }
                IN_DAYS.matches(text) -> day(today.plusDays(IN_DAYS.find(text)!!.groupValues[1].toLong()))
                DAYS_AGO.matches(text) -> day(today.minusDays(DAYS_AGO.find(text)!!.groupValues[1].toLong()))
                RANGE.matches(text) ->
                    RANGE.find(text)!!.groupValues.let { g ->
                        val a = parse(g[1]) ?: return null
                        val b = parse(g[2]) ?: return null
                        if (b.isBefore(a)) null else DateRange(a, b)
                    }
                else -> parse(text)?.let(::day)
            }
        }

        private fun parse(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

        private val IN_DAYS = Regex("""^in (\d+) days?$""")
        private val DAYS_AGO = Regex("""^(\d+) days? ago$""")
        private val RANGE = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{4}-\d{2}-\d{2})$""")
    }
}
