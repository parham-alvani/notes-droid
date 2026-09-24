package me.parham1995.notes.obsidian

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Dates written the way moment.js writes them, which is how Obsidian names a
 * daily note.
 *
 * Obsidian hands the format string straight to moment, so this has to agree
 * with moment rather than with java.time's own pattern letters -- which mean
 * different things for most of the same characters: `DD` is day of the month
 * to moment and day of the year to java.time, `YYYY` is a calendar year to one
 * and a week-based year to the other. Rather than translate one pattern
 * language into the other and inherit both sets of quoting rules, the format
 * is read token by token and each is answered from the date directly.
 *
 * Covered: years (`YYYY`, `YY`), quarters (`Q`), months (`M` `MM` `MMM`
 * `MMMM` `Mo`), days of the month (`D` `DD` `Do`) and year (`DDD` `DDDD`),
 * weekdays (`d` `dd` `ddd` `dddd` `do` `e` `E`), weeks and week years, locale
 * (`w` `ww` `gg` `gggg`) and ISO (`W` `WW` `GG` `GGGG`), `[literal text]`,
 * and `\x` for one literal character. Anything else is copied through as it
 * stands, which is also what moment does with a character it does not know.
 */
object MomentFormat {
    private val TOKENS =
        Regex(
            """\[[^\[]*]|\\.|Mo|MMMM|MMM|MM|M|Do|DDDD|DDD|DD|D|dddd|ddd|dd|do|d|wo|ww|w|Wo|WW|W|Qo|Q""" +
                """|YYYY|YY|gggg|gg|GGGG|GG|e|E|.""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /**
     * Moment's English week: it starts on Sunday, and the first week of a year
     * is the one holding the 1st of January.
     */
    internal val LOCALE_WEEK: WeekFields = WeekFields.of(DayOfWeek.SUNDAY, 1)

    /** [pattern] cut into the tokens moment reads it as, literals included. */
    internal fun tokens(pattern: String): List<String> = TOKENS.findAll(pattern).map { it.value }.toList()

    fun format(
        pattern: String,
        date: LocalDate,
        locale: Locale = Locale.ENGLISH,
    ): String =
        buildString {
            tokens(pattern).forEach { append(token(it, date, locale)) }
        }

    private fun token(
        token: String,
        date: LocalDate,
        locale: Locale,
    ): String =
        when (token) {
            "YYYY" -> date.year.toString().padStart(YEAR_WIDTH, '0')
            "YY" -> (Math.floorMod(date.year, CENTURY)).toString().padStart(2, '0')
            "Q" -> date.get(IsoFields.QUARTER_OF_YEAR).toString()
            "Qo" -> ordinal(date.get(IsoFields.QUARTER_OF_YEAR))
            "MMMM" -> date.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
            "MMM" -> date.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
            "MM" -> date.monthValue.toString().padStart(2, '0')
            "M" -> date.monthValue.toString()
            "Mo" -> ordinal(date.monthValue)
            "DDDD" -> date.dayOfYear.toString().padStart(DAY_OF_YEAR_WIDTH, '0')
            "DDD" -> date.dayOfYear.toString()
            "DD" -> date.dayOfMonth.toString().padStart(2, '0')
            "D" -> date.dayOfMonth.toString()
            "Do" -> ordinal(date.dayOfMonth)
            "dddd" -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            "ddd" -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            // moment's "Su", "Mo": the short name cut to two letters.
            "dd" -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).take(2)
            // Sunday is 0 to moment, and 7 to java.time.
            "d", "e" -> sundayFirst(date).toString()
            "do" -> ordinal(sundayFirst(date))
            "E" -> date.dayOfWeek.value.toString()
            "w" -> date.get(LOCALE_WEEK.weekOfWeekBasedYear()).toString()
            "ww" -> date.get(LOCALE_WEEK.weekOfWeekBasedYear()).toString().padStart(2, '0')
            "wo" -> ordinal(date.get(LOCALE_WEEK.weekOfWeekBasedYear()))
            "gggg" -> date.get(LOCALE_WEEK.weekBasedYear()).toString().padStart(YEAR_WIDTH, '0')
            "gg" -> Math.floorMod(date.get(LOCALE_WEEK.weekBasedYear()), CENTURY).toString().padStart(2, '0')
            "W" -> date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString()
            "WW" -> date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString().padStart(2, '0')
            "Wo" -> ordinal(date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
            "GGGG" -> date.get(IsoFields.WEEK_BASED_YEAR).toString().padStart(YEAR_WIDTH, '0')
            "GG" -> Math.floorMod(date.get(IsoFields.WEEK_BASED_YEAR), CENTURY).toString().padStart(2, '0')
            else ->
                when {
                    token.length > 1 && token.startsWith("[") -> token.substring(1, token.length - 1)
                    token.length == 2 && token.startsWith("\\") -> token.substring(1)
                    else -> token
                }
        }

    private fun sundayFirst(date: LocalDate): Int = date.get(ChronoField.DAY_OF_WEEK) % DAYS_IN_WEEK

    /** English ordinals, as moment's default locale writes them. */
    private fun ordinal(n: Int): String {
        val suffix =
            if (n % 100 in TEENS) {
                "th"
            } else {
                when (n % 10) {
                    1 -> "st"
                    2 -> "nd"
                    3 -> "rd"
                    else -> "th"
                }
            }
        return "$n$suffix"
    }

    private const val YEAR_WIDTH = 4
    private const val DAY_OF_YEAR_WIDTH = 3
    private const val CENTURY = 100
    private const val DAYS_IN_WEEK = 7
    private val TEENS = 11..13
}
