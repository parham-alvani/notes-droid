package me.parham1995.notes.obsidian

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.IsoFields

/** One meeting, as a week note says it. */
data class WeekMeeting(
    val date: LocalDate,
    /** Epoch millis; both null for an all-day event. */
    val start: Long? = null,
    val end: Long? = null,
    val title: String,
    val calendars: List<String> = emptyList(),
    val join: String? = null,
    val tentative: Boolean = false,
    /** The calendar is set to busy only: the time is real, the rest is not in the note. */
    val busyOnly: Boolean = false,
    val overlaps: String? = null,
) {
    val allDay: Boolean get() = start == null
}

/** One `## Monday 26 October` section of a week note. */
data class WeekDay(
    val date: LocalDate,
    /** The free time as the note writes it, `09:00–10:30, 14:00–18:00`, or `none`. */
    val free: String? = null,
    val meetings: List<WeekMeeting> = emptyList(),
)

/**
 * Reads back the week notes [CalendarWeeks] writes into `Calendar/`, the way
 * the Mac app's `CalendarWeek` reads them: a heading per day, a `Free:` line,
 * and a list item per meeting with its join link and clashes under it.
 */
object WeekNotes {
    private val NAME = Regex("""(\d{4})-W(\d{2})\.md$""")
    private val ZONE = Regex("""Times are ([A-Za-z_]+/[A-Za-z_/+-]+|UTC)""")
    private val DAY_HEADING = Regex("""^## \w+ (\d{1,2}) (\w+)\s*$""")
    private val EVENT = Regex("""^- (All day|(\d{2}):(\d{2})–(?:\w{3} )?(\d{2}):(\d{2})) (.*)$""")
    private val CALENDARS = Regex("""\s*\(([^()]*)\)$""")
    private val DETAIL = Regex("""^\s{2,}- (\w+): (.*)$""")
    private val AUTOLINK = Regex("""<((?:https?://|[^\s<>]+@)[^\s<>]+)>""")
    private const val MAYBE = " (maybe)"
    private const val DAY_MS = 86_400_000L

    private val MONTHS =
        listOf(
            "january",
            "february",
            "march",
            "april",
            "may",
            "june",
            "july",
            "august",
            "september",
            "october",
            "november",
            "december",
        )

    /** The days of one week note. [path] gives the year, which the headings leave out. */
    fun parse(
        text: String,
        path: String,
        fallbackZone: ZoneId = ZoneId.systemDefault(),
    ): List<WeekDay> {
        val name = NAME.find(path) ?: return emptyList()
        val monday =
            runCatching {
                LocalDate
                    .of(name.groupValues[1].toInt(), 1, 4)
                    .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, name.groupValues[2].toLong())
                    .let(CalendarWeeks::mondayOf)
            }.getOrNull() ?: return emptyList()
        val zone =
            ZONE
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallbackZone

        val days = mutableListOf<WeekDay>()
        for (line in text.split('\n').map { it.trimEnd('\r') }) {
            val heading = DAY_HEADING.matchEntire(line)
            if (heading != null) {
                dayOf(monday, heading.groupValues[1].toInt(), heading.groupValues[2])?.let { days += WeekDay(it) }
                continue
            }
            val current = days.lastOrNull() ?: continue
            if (line.startsWith("Free: ")) {
                days[days.lastIndex] = current.copy(free = line.removePrefix("Free: ").trim('.', ' '))
                continue
            }
            val event = EVENT.matchEntire(line)
            if (event != null) {
                days[days.lastIndex] = current.copy(meetings = current.meetings + meeting(current.date, event, zone))
                continue
            }
            val detail = DETAIL.matchEntire(line) ?: continue
            val last = current.meetings.lastOrNull() ?: continue
            val value = detail.groupValues[2]
            val changed =
                when (detail.groupValues[1]) {
                    "Join" -> last.copy(join = value.trim('<', '>'))
                    "Overlaps" -> last.copy(overlaps = unescape(value))
                    else -> continue
                }
            days[days.lastIndex] = current.copy(meetings = current.meetings.dropLast(1) + changed)
        }
        return days
    }

    /** The date a heading names: the week's Monday fixes the year, since a week can cross into January. */
    private fun dayOf(
        monday: LocalDate,
        date: Int,
        month: String,
    ): LocalDate? {
        val number = MONTHS.indexOf(month.lowercase()) + 1
        if (number == 0) return null
        return listOf(monday.year, monday.year + 1)
            .mapNotNull { year -> runCatching { LocalDate.of(year, number, date) }.getOrNull() }
            .minByOrNull { kotlin.math.abs(it.toEpochDay() - monday.toEpochDay()) }
    }

    private fun meeting(
        date: LocalDate,
        event: MatchResult,
        zone: ZoneId,
    ): WeekMeeting {
        val g = event.groupValues
        var rest = g[6]
        var calendars = emptyList<String>()
        CALENDARS.find(rest)?.let { suffix ->
            calendars =
                suffix.groupValues[1]
                    .split(',')
                    .map { it.trim(' ', '<', '>') }
                    .filter { it.isNotEmpty() }
            rest = rest.substring(0, suffix.range.first)
        }
        val tentative = rest.endsWith(MAYBE)
        if (tentative) rest = rest.removeSuffix(MAYBE)
        var start: Long? = null
        var end: Long? = null
        if (g[2].isNotEmpty()) {
            try {
                start =
                    date
                        .atTime(LocalTime.of(g[2].toInt(), g[3].toInt()))
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli()
                var finish =
                    date
                        .atTime(
                            LocalTime.of(g[4].toInt(), g[5].toInt()),
                        ).atZone(zone)
                        .toInstant()
                        .toEpochMilli()
                // `23:00–Sat 01:00` ends on a later day.
                while (finish <= start) finish += DAY_MS
                end = finish
            } catch (_: DateTimeException) {
                start = null
                end = null
            }
        }
        return WeekMeeting(
            date = date,
            start = start,
            end = end,
            title = unescape(rest),
            calendars = calendars,
            tentative = tentative,
            busyOnly = rest == "Busy",
        )
    }

    /** The week notes escape markdown in titles so the vault's lint passes; this takes it back out. */
    internal fun unescape(text: String): String {
        val unlinked = AUTOLINK.replace(text) { it.groupValues[1] }
        return buildString {
            var at = 0
            while (at < unlinked.length) {
                val ch = unlinked[at]
                if (ch == '\\' &&
                    at + 1 < unlinked.length &&
                    !unlinked[at + 1].isLetterOrDigit() &&
                    !unlinked[at + 1].isWhitespace()
                ) {
                    append(unlinked[at + 1])
                    at += 2
                } else {
                    append(ch)
                    at++
                }
            }
        }
    }
}
