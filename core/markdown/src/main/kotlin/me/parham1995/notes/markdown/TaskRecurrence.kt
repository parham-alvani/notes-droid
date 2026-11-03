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

enum class RecurrenceUnit {
    DAY,
    WEEK,
    MONTH,
    YEAR,

    /** `every jalali month`: the same day of the Jalali month. */
    JALALI_MONTH,

    /** `every jalali year`. */
    JALALI_YEAR,
}

/**
 * The repeat rules the app is willing to act on.
 *
 * Deliberately a subset. The Tasks plugin parses these with a full recurrence
 * library, and the rules that library accepts run to weekday lists, ordinals
 * and month names -- getting one of those subtly wrong writes a wrong date into
 * a vault somebody relies on. So the plain intervals are handled and everything
 * else is refused out loud, which is worse than handling it and far better than
 * guessing.
 *
 * The plain intervals include months and years of the Jalali calendar, `every
 * jalali month`, for a bill that falls on a day of the Jalali month: the dates
 * stay Gregorian and only the step is taken in the Jalali calendar. Daftar for
 * Linux and the Mac read the same rules, `jalali-recurrence.md` in notes-linux's
 * `docs/specs/`.
 */
object TaskRecurrence {
    private val EVERY = Regex("""^every(?:\s+(\d+))?\s+(?:(jalali)\s+(month|year)|(day|week|month|year))s?$""")

    /** Null when the rule is not one of the plain intervals. */
    fun parse(rule: String): Recurrence? {
        var text = rule.trim().lowercase()
        val whenDone = text.endsWith(WHEN_DONE)
        if (whenDone) text = text.removeSuffix(WHEN_DONE).trim()

        val match = EVERY.find(text) ?: return null
        val written = match.groupValues[1]
        // Too many digits for a number is not "every 1": that read a rule
        // nobody wrote and moved the task by it.
        val amount = if (written.isEmpty()) 1L else written.toLongOrNull() ?: return null
        // Past this, a step runs off the end of the calendar and throws a
        // DateTimeException out of the tick; refused, it is said out loud.
        if (amount <= 0 || amount > MAX_AMOUNT) return null
        val unit =
            when (match.groupValues[3]) {
                "month" -> RecurrenceUnit.JALALI_MONTH
                "year" -> RecurrenceUnit.JALALI_YEAR
                else ->
                    when (match.groupValues[4]) {
                        "day" -> RecurrenceUnit.DAY
                        "week" -> RecurrenceUnit.WEEK
                        "month" -> RecurrenceUnit.MONTH
                        else -> RecurrenceUnit.YEAR
                    }
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
     *
     * Null when a step runs off the Jalali calendar, which ends with its year
     * 3177: there is no next day to write, and the tick finishes the task
     * without one rather than failing.
     */
    fun next(
        from: LocalDate,
        rule: Recurrence,
        notBefore: LocalDate,
    ): LocalDate? {
        var date = step(from, rule) ?: return null
        var guard = 0
        while (!date.isAfter(notBefore) && guard++ < MAX_STEPS) {
            date = step(date, rule) ?: return null
        }
        return date
    }

    private fun step(
        from: LocalDate,
        rule: Recurrence,
    ): LocalDate? =
        when (rule.unit) {
            RecurrenceUnit.DAY -> from.plusDays(rule.amount)
            RecurrenceUnit.WEEK -> from.plusWeeks(rule.amount)
            RecurrenceUnit.MONTH -> from.plusMonths(rule.amount)
            RecurrenceUnit.YEAR -> from.plusYears(rule.amount)
            RecurrenceUnit.JALALI_MONTH -> Jalali.addingMonths(from, rule.amount)
            RecurrenceUnit.JALALI_YEAR -> Jalali.addingMonths(from, rule.amount * MONTHS_IN_YEAR)
        }

    private const val WHEN_DONE = "when done"
    private const val MAX_AMOUNT = 10_000L
    private const val MONTHS_IN_YEAR = 12L

    /** A daily rule on a task abandoned for years still terminates. */
    private const val MAX_STEPS = 4000
}
