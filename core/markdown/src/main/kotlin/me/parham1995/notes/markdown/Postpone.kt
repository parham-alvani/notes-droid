package me.parham1995.notes.markdown

import java.time.LocalDate

/**
 * How far the Tasks plugin's Postpone menu moves a task, as the Mac offers
 * it too: a day, two, three, a week, two weeks, a month.
 */
enum class Postpone {
    DAY,
    TWO_DAYS,
    THREE_DAYS,
    WEEK,
    TWO_WEEKS,
    MONTH,
    ;

    /**
     * The day a task filed under [filed] -- due, else scheduled -- moves to.
     * A date already past, or today, moves on from today, so an overdue
     * task is not overdue again the moment it is put off.
     */
    fun from(
        filed: LocalDate,
        today: LocalDate,
    ): LocalDate {
        val start = maxOf(filed, today)
        return when (this) {
            DAY -> start.plusDays(1)
            TWO_DAYS -> start.plusDays(2)
            THREE_DAYS -> start.plusDays(3)
            WEEK -> start.plusWeeks(1)
            TWO_WEEKS -> start.plusWeeks(2)
            MONTH -> start.plusMonths(1)
        }
    }
}
