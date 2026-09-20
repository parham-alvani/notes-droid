package me.parham1995.notes.markdown

import java.time.LocalDate

/** How often a task comes back. */
data class Recurrence(
    val amount: Long,
    val unit: RecurrenceUnit,
    /**
     * Count from the day it was ticked rather than from the date it carried.
     *
     * The Tasks plugin's `when done`. Without it a weekly task done three days
     * late still comes back on its original weekday; with it, a week from
     * today.
     */
    val whenDone: Boolean,
)

enum class RecurrenceUnit { DAY, WEEK, MONTH, YEAR }

/**
 * The repeat rules the app is willing to act on.
 *
 * Deliberately a subset. The Tasks plugin parses these with a full recurrence
 * library, and the rules that library accepts run to weekday lists, ordinals
 * and month names -- getting one of those subtly wrong writes a wrong date into
 * a vault somebody relies on. So the plain intervals are handled and everything
 * else is refused out loud, which is worse than handling it and far better than
 * guessing.
 */
object TaskRecurrence {
    private val EVERY = Regex("""^every(?:\s+(\d+))?\s+(day|week|month|year)s?$""")

    /** Null when the rule is not one of the plain intervals. */
    fun parse(rule: String): Recurrence? {
        var text = rule.trim().lowercase()
        val whenDone = text.endsWith(WHEN_DONE)
        if (whenDone) text = text.removeSuffix(WHEN_DONE).trim()

        val match = EVERY.find(text) ?: return null
        val amount = match.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 1L
        if (amount <= 0) return null
        val unit =
            when (match.groupValues[2]) {
                "day" -> RecurrenceUnit.DAY
                "week" -> RecurrenceUnit.WEEK
                "month" -> RecurrenceUnit.MONTH
                else -> RecurrenceUnit.YEAR
            }
        return Recurrence(amount, unit, whenDone)
    }

    /**
     * The next occurrence after [notBefore], stepping from [from].
     *
     * Kept stepping until it lands in the future, rather than adding one
     * interval and stopping. A daily task ticked off ten days late would
     * otherwise come back yesterday -- still overdue, and overdue again on the
     * next tick, for ever.
     */
    fun next(
        from: LocalDate,
        rule: Recurrence,
        notBefore: LocalDate,
    ): LocalDate {
        var date = step(from, rule)
        var guard = 0
        while (!date.isAfter(notBefore) && guard++ < MAX_STEPS) {
            date = step(date, rule)
        }
        return date
    }

    private fun step(
        from: LocalDate,
        rule: Recurrence,
    ): LocalDate =
        when (rule.unit) {
            RecurrenceUnit.DAY -> from.plusDays(rule.amount)
            RecurrenceUnit.WEEK -> from.plusWeeks(rule.amount)
            RecurrenceUnit.MONTH -> from.plusMonths(rule.amount)
            RecurrenceUnit.YEAR -> from.plusYears(rule.amount)
        }

    private const val WHEN_DONE = "when done"

    /** A daily rule on a task abandoned for years still terminates. */
    private const val MAX_STEPS = 4000
}
