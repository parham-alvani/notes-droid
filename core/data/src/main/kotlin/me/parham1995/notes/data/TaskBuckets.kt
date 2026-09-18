package me.parham1995.notes.data

import java.time.LocalDate

/** How a task list is grouped, in the order it is shown. */
enum class TaskBucket {
    OVERDUE,
    TODAY,
    TOMORROW,
    THIS_WEEK,
    LATER,

    /**
     * Open, but with no date on it at all. Last rather than absent: in this
     * vault an undated task is usually one that was written down and never
     * scheduled, which is worth seeing, but never worth putting above
     * something that is actually late.
     */
    UNDATED,
    ;

    val label: String
        get() =
            when (this) {
                OVERDUE -> "Overdue"
                TODAY -> "Today"
                TOMORROW -> "Tomorrow"
                THIS_WEEK -> "This week"
                LATER -> "Later"
                UNDATED -> "No date"
            }
}

/**
 * Which bucket a date falls in, relative to a given day.
 *
 * Takes today as an argument rather than reading the clock so the boundaries
 * are testable -- every interesting case here is a boundary.
 */
object TaskBuckets {
    private const val WEEK_DAYS = 7L

    fun of(
        actionableOn: String?,
        today: LocalDate,
    ): TaskBucket {
        val date =
            actionableOn
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                // A date the plugin wrote in some other shape is not a reason
                // to drop the task; it is simply not a date we can sort by.
                ?: return TaskBucket.UNDATED

        return when {
            date.isBefore(today) -> TaskBucket.OVERDUE
            date == today -> TaskBucket.TODAY
            date == today.plusDays(1) -> TaskBucket.TOMORROW
            date.isBefore(today.plusDays(WEEK_DAYS)) -> TaskBucket.THIS_WEEK
            else -> TaskBucket.LATER
        }
    }
}
