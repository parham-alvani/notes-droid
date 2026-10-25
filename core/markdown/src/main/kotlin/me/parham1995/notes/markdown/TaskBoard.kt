package me.parham1995.notes.markdown

import java.time.LocalDate

/**
 * A vault's tasks as a board: columns by status, by day or by file.
 *
 * The same board the Mac app draws, by the same rules, because the two apps
 * read one vault and a task should not sit in one column at the desk and
 * another on the phone. A card moved to a status or a day column changes that
 * task's line and nothing else ([VaultEdits.setStatus], [VaultEdits.reschedule]);
 * the file columns are for looking, and take no drop.
 *
 * Generic over the row a caller holds, with [Facts] read off each one, so the
 * rules live here with no Android in sight and the app keeps its own rows.
 */
object TaskBoard {
    enum class Grouping { STATUS, DAY, FILE }

    /** What a column writes on a task moved to it. */
    sealed interface Drop {
        /** The status character: ` `, `/`, `x` or `-`. */
        data class Status(
            val status: Char,
        ) : Drop

        /** The day it is filed under; null takes every such date off. */
        data class Day(
            val date: String?,
        ) : Drop
    }

    /** What the board needs to know about one task. */
    data class Facts(
        val state: TaskState,
        val path: String,
        /** The note's title, which names a file column. */
        val title: String,
        val line: Int,
        /** Due, else scheduled: the day it is answerable on. */
        val day: String?,
        val priority: String?,
        val done: String?,
        val cancelled: String?,
    ) {
        val isOpen: Boolean get() = state == TaskState.UNCHECKED || state == TaskState.IN_PROGRESS
    }

    data class Column<T>(
        /** `todo`, `doing`, `done`, `cancelled`; `overdue` … `none`; or a note's path. */
        val id: String,
        /** In English for the fixed columns -- the app names them -- and the note's title for a file. */
        val title: String,
        val tasks: List<T>,
        /** Null for a column nothing can be moved to, as Overdue. */
        val drop: Drop?,
    )

    /** How long a closed task stays on the board, in days. */
    const val CLOSED_WITHIN = 14

    /**
     * The board of [tasks]. Done and cancelled tasks are shown when they
     * closed -- by their `✅` and `❌` dates -- within [closedWithin] days of
     * [today], or all of them when it is null.
     */
    fun <T> columns(
        tasks: List<T>,
        by: Grouping,
        today: LocalDate,
        closedWithin: Int? = CLOSED_WITHIN,
        facts: (T) -> Facts,
    ): List<Column<T>> {
        val known = tasks.map { it to facts(it) }
        val open =
            compareBy<Pair<T, Facts>>({ it.second.day ?: NO_DAY }, { rank(it.second.priority) })
                .thenBy { it.second.path }
                .thenBy { it.second.line }

        fun List<Pair<T, Facts>>.sortedOpen() = sortedWith(open).map { it.first }

        return when (by) {
            Grouping.STATUS -> {
                val since = closedWithin?.let { today.minusDays(it.toLong()) }

                fun recent(date: String?) = since == null || date?.toDateOrNull()?.let { !it.isBefore(since) } == true
                val byState = known.groupBy { it.second.state }

                fun closed(
                    state: TaskState,
                    date: (Facts) -> String?,
                ) = byState[state]
                    .orEmpty()
                    .filter { recent(date(it.second)) }
                    .sortedWith(
                        compareByDescending<Pair<T, Facts>> { date(it.second).orEmpty() }
                            .thenBy { it.second.path }
                            .thenBy { it.second.line },
                    ).map { it.first }
                listOf(
                    Column("todo", "To Do", byState[TaskState.UNCHECKED].orEmpty().sortedOpen(), Drop.Status(' ')),
                    Column(
                        "doing",
                        "In Progress",
                        byState[TaskState.IN_PROGRESS].orEmpty().sortedOpen(),
                        Drop.Status('/'),
                    ),
                    Column("done", "Done", closed(TaskState.CHECKED) { it.done }, Drop.Status('x')),
                    Column("cancelled", "Cancelled", closed(TaskState.CANCELLED) { it.cancelled }, Drop.Status('-')),
                )
            }

            Grouping.DAY -> {
                val tomorrow = today.plusDays(1)
                val week = today.plusDays(WEEK)
                val byDay =
                    known.filter { it.second.isOpen }.groupBy { (_, task) ->
                        val day = task.day?.toDateOrNull()
                        when {
                            day == null -> "none"
                            day.isBefore(today) -> "overdue"
                            day == today -> "today"
                            day == tomorrow -> "tomorrow"
                            !day.isAfter(week) -> "week"
                            else -> "later"
                        }
                    }
                listOf(
                    Triple("overdue", "Overdue", null),
                    Triple("today", "Today", Drop.Day(today.toString())),
                    Triple("tomorrow", "Tomorrow", Drop.Day(tomorrow.toString())),
                    Triple("week", "Next 7 Days", Drop.Day(week.toString())),
                    Triple("later", "Later", null),
                    Triple("none", "No Date", Drop.Day(null)),
                ).map { (id, title, drop) -> Column(id, title, byDay[id].orEmpty().sortedOpen(), drop) }
            }

            Grouping.FILE ->
                known
                    .filter { it.second.isOpen }
                    .groupBy { it.second.path }
                    .entries
                    .sortedWith(
                        compareBy({
                            it.value
                                .first()
                                .second.title
                                .lowercase()
                        }, { it.key }),
                    ).map { (path, theirs) ->
                        Column(path, theirs.first().second.title, theirs.sortedOpen(), null)
                    }
        }
    }

    /** Where a priority sorts: highest first, none in the middle, as the Tasks plugin has it. */
    fun rank(priority: String?): Int =
        when (priority) {
            "🔺" -> 0
            "⏫" -> 1
            "🔼" -> 2
            "🔽" -> 4
            "⏬" -> 5
            else -> 3
        }

    /** The columns a task can be moved to under [by], in board order. */
    fun drops(
        by: Grouping,
        today: LocalDate,
    ): List<Column<Nothing>> = columns(emptyList<Nothing>(), by, today) { error("no tasks") }.filter { it.drop != null }

    private fun String.toDateOrNull(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

    /** Sorts after every real date, so a task with no day comes last. */
    private const val NO_DAY = "9999"
    private const val WEEK = 7L
}
