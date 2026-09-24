package me.parham1995.notes.obsidian

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** How much time one note of a daily-notes format covers. */
enum class Period { DAY, WEEK, MONTH, QUARTER, YEAR }

/**
 * The daily notes read as a series: which notes belong to it, what stretch
 * of time each one is, and which comes before and after.
 *
 * The Daily notes plugin names a note by formatting a date, and a format with
 * no day in it -- `YYYY-[W]ww`, `YYYY-MM` -- names one note for a whole week
 * or month. So the series is really of periods, and how long one is follows
 * from the smallest thing the format writes.
 *
 * A period is identified by the day it starts on: the date itself, the first
 * day of the week (Sunday for moment's English `w`, Monday for ISO `W`), of
 * the month, of the quarter, of the year.
 *
 * Reading a name back is done loosely and then checked strictly: the fields
 * are pulled out with a pattern built from the format, turned into a period,
 * and the name is accepted only if some day of that period formats to it
 * again. Whatever moment would not have written is not a periodic note, so
 * `2026-02-30` and `2026-3-4` (for `YYYY-MM-DD`) are ordinary notes.
 */
class PeriodicNotes(
    private val config: DailyNotes,
    private val locale: Locale = Locale.ENGLISH,
) {
    private val tokens = MomentFormat.tokens(config.format)
    private val folder = config.folder.trim().trim('/')

    val period: Period =
        when {
            tokens.any { it in DAY_TOKENS } -> Period.DAY
            tokens.any { it in WEEK_TOKENS } -> Period.WEEK
            tokens.any { it in MONTH_TOKENS } -> Period.MONTH
            tokens.any { it in QUARTER_TOKENS } -> Period.QUARTER
            else -> Period.YEAR
        }

    /** ISO weeks when the format says `W` or `G`, moment's English weeks otherwise. */
    private val weeks: WeekFields =
        if (tokens.any { it in ISO_WEEK_TOKENS }) WeekFields.ISO else MomentFormat.LOCALE_WEEK

    /** The token each capturing group of [pattern] stands for, in order. */
    private val groups = mutableListOf<String>()
    private val pattern: Regex =
        Regex(tokens.joinToString("") { token -> regexFor(token)?.also { groups += token } ?: literal(token) })

    /** The first day of the period [date] falls in. */
    fun startOf(date: LocalDate): LocalDate =
        when (period) {
            Period.DAY -> date
            Period.WEEK -> date.with(TemporalAdjusters.previousOrSame(weeks.firstDayOfWeek))
            Period.MONTH -> date.withDayOfMonth(1)
            Period.QUARTER -> date.withDayOfMonth(1).withMonth((date.monthValue - 1) / 3 * 3 + 1)
            Period.YEAR -> date.withDayOfYear(1)
        }

    /** The start of the period [by] periods after the one starting on [start]. */
    fun shift(
        start: LocalDate,
        by: Long,
    ): LocalDate =
        when (period) {
            Period.DAY -> start.plusDays(by)
            Period.WEEK -> start.plusWeeks(by)
            Period.MONTH -> start.plusMonths(by)
            Period.QUARTER -> start.plusMonths(by * MONTHS_IN_QUARTER)
            Period.YEAR -> start.plusYears(by)
        }

    /**
     * The period [path] is the note of, by its start; null when it is not a
     * daily note of this vault at all.
     *
     * A name can be written by two periods. `YYYY` is the calendar year, and
     * moment's week 1 is the week holding the 1st of January, so the last days
     * of December are written as week 1 of the year they are still in:
     * `YYYY-[W]ww` names both the first week of 2026 and 27--31 December 2026
     * `2026-W01`. The reading taken is the natural one -- week 1 of 2026 --
     * which is also the only one the file can have been made for first.
     */
    fun periodOf(path: String): LocalDate? {
        val name = nameOf(path) ?: return null
        val match = pattern.matchEntire(name) ?: return null
        val fields = groups.zip(match.groupValues.drop(1)).toMap()
        val start = candidate(fields)?.let(::startOf) ?: return null
        return start.takeIf { days(it).any { day -> format(day) == name } }
    }

    /**
     * The path of the note for the period starting on [start].
     *
     * Usually the name of any of its days. Where the days disagree -- the week
     * that straddles New Year under `YYYY-[W]ww` writes both `2026-W01` and
     * `2027-W01` -- it is the name that reads back as this period, so stepping
     * forward from the last week of 2026 does not land on the first of 2026.
     */
    fun pathOf(start: LocalDate): String {
        val names = days(start).map(::format).distinct()
        val own = names.firstOrNull { periodOf(pathFrom(it)) == start } ?: names.first()
        return pathFrom(own)
    }

    /** The path of the period before [path]'s, or null when [path] is not a daily note. */
    fun previous(path: String): String? = periodOf(path)?.let { pathOf(shift(it, -1)) }

    /** The path of the period after [path]'s, or null when [path] is not a daily note. */
    fun next(path: String): String? = periodOf(path)?.let { pathOf(shift(it, 1)) }

    /**
     * Of [paths], the daily note closest to [from] in the direction [step]
     * points (negative is earlier). Not necessarily the adjacent period: a
     * weekly journal kept in fits and starts has gaps, and the note before a
     * gap is more use than being told the week before has none.
     */
    fun nearest(
        paths: Collection<String>,
        from: String,
        step: Int,
    ): String? {
        val here = periodOf(from) ?: return null
        val periods = paths.mapNotNull { path -> periodOf(path)?.let { it to path } }
        return if (step < 0) {
            periods.filter { it.first < here }.maxWithOrNull(compareBy({ it.first }, { it.second }))?.second
        } else {
            periods.filter { it.first > here }.minWithOrNull(compareBy({ it.first }, { it.second }))?.second
        }
    }

    private fun days(start: LocalDate): Sequence<LocalDate> {
        val end = shift(start, 1)
        return generateSequence(start) { it.plusDays(1) }.takeWhile { it < end }
    }

    private fun format(date: LocalDate): String = MomentFormat.format(config.format, date, locale).trim('/')

    private fun pathFrom(name: String): String = (if (folder.isEmpty()) name else "$folder/$name") + ".md"

    private fun nameOf(path: String): String? {
        if (!path.endsWith(".md")) return null
        val inside =
            if (folder.isEmpty()) {
                path
            } else {
                path.removePrefix("$folder/").takeIf { it != path } ?: return null
            }
        return inside.removeSuffix(".md")
    }

    /** A day inside the period the fields describe, when they describe one. */
    private fun candidate(fields: Map<String, String>): LocalDate? {
        fun int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { fields[it] }?.let(::number)
        val year = int("YYYY") ?: int("YY")?.let(::century)
        val weekYear =
            if (weeks == WeekFields.ISO) {
                int("GGGG") ?: int("GG")?.let(::century)
            } else {
                int("gggg") ?: int("gg")?.let(::century)
            } ?: year
        val month = int("MMMM", "MMM", "MM", "M", "Mo")
        val dayOfMonth = int("DD", "D", "Do")
        val dayOfYear = int("DDDD", "DDD")
        val week = int("ww", "w", "wo", "WW", "W", "Wo")
        val weekday = int("dddd", "ddd", "dd", "d", "do", "e")?.let { if (it == 0) 7 else it } ?: int("E")
        val quarter = int("Q", "Qo")
        return try {
            when {
                year != null && month != null && dayOfMonth != null -> LocalDate.of(year, month, dayOfMonth)
                year != null && dayOfYear != null -> LocalDate.ofYearDay(year, dayOfYear)
                weekYear != null && week != null -> {
                    val start =
                        LocalDate
                            .of(weekYear, MID_YEAR, 1)
                            .with(weeks.weekBasedYear(), weekYear.toLong())
                            .with(weeks.weekOfWeekBasedYear(), week.toLong())
                            .with(TemporalAdjusters.previousOrSame(weeks.firstDayOfWeek))
                    when {
                        weekday != null -> start.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(weekday)))
                        period == Period.DAY -> null
                        else -> start
                    }
                }
                period == Period.DAY -> null
                year != null && month != null -> LocalDate.of(year, month, 1)
                year != null && quarter != null -> LocalDate.of(year, (quarter - 1) * MONTHS_IN_QUARTER + 1, 1)
                year != null && period == Period.YEAR -> LocalDate.of(year, 1, 1)
                else -> null
            }
        } catch (_: DateTimeException) {
            null
        }
    }

    /** Numbers as they were written, and names as the number they stand for. */
    private fun number(text: String): Int? =
        text.toIntOrNull()
            ?: text.trimEnd { it.isLetter() }.toIntOrNull()
            ?: monthNames[text]
            ?: weekdayNames[text]

    private val monthNames: Map<String, Int> =
        Month.entries
            .flatMap { month ->
                listOf(TextStyle.FULL_STANDALONE, TextStyle.SHORT_STANDALONE)
                    .map { month.getDisplayName(it, locale) to month.value }
            }.toMap()

    /** Sunday-first, as moment numbers them, with Sunday as 7 once read. */
    private val weekdayNames: Map<String, Int> =
        DayOfWeek.entries
            .flatMap { day ->
                val short = day.getDisplayName(TextStyle.SHORT, locale)
                listOf(day.getDisplayName(TextStyle.FULL, locale), short, short.take(2)).map { it to day.value }
            }.toMap()

    private fun names(values: Collection<String>): String =
        "(" + values.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")"

    private fun regexFor(token: String): String? =
        when (token) {
            "YYYY", "gggg", "GGGG" -> """(\d{4})"""
            "YY", "gg", "GG", "MM", "DD", "ww", "WW" -> """(\d{2})"""
            "M", "D", "w", "W" -> """(\d{1,2})"""
            "Mo", "Do", "wo", "Wo" -> """(\d{1,2}(?:st|nd|rd|th))"""
            "Q", "d", "e", "E" -> """(\d)"""
            "Qo", "do" -> """(\d(?:st|nd|rd|th))"""
            "DDDD" -> """(\d{3})"""
            "DDD" -> """(\d{1,3})"""
            "MMMM" -> names(Month.entries.map { it.getDisplayName(TextStyle.FULL_STANDALONE, locale) })
            "MMM" -> names(Month.entries.map { it.getDisplayName(TextStyle.SHORT_STANDALONE, locale) })
            "dddd" -> names(DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, locale) })
            "ddd" -> names(DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, locale) })
            "dd" -> names(DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, locale).take(2) })
            else -> null
        }

    /** A token that is not a field, as the text moment writes for it. */
    private fun literal(token: String): String =
        Regex.escape(
            when {
                token.length > 1 && token.startsWith("[") -> token.substring(1, token.length - 1)
                token.length == 2 && token.startsWith("\\") -> token.substring(1)
                else -> token
            },
        )

    private companion object {
        val DAY_TOKENS = setOf("D", "DD", "Do", "DDD", "DDDD", "d", "dd", "ddd", "dddd", "do", "e", "E")
        val WEEK_TOKENS = setOf("w", "ww", "wo", "W", "WW", "Wo")
        val ISO_WEEK_TOKENS = setOf("W", "WW", "Wo", "GGGG", "GG")
        val MONTH_TOKENS = setOf("M", "MM", "Mo", "MMM", "MMMM")
        val QUARTER_TOKENS = setOf("Q", "Qo")
        const val MONTHS_IN_QUARTER = 3
        const val MID_YEAR = 7

        /** moment's reading of a two-digit year: up to 68 is this century. */
        fun century(yy: Int): Int = yy + if (yy > 68) 1900 else 2000
    }
}
