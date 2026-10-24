package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarWeeksTest {
    private val zone = ZoneId.of("Europe/Madrid")

    /** Monday of ISO week 43. */
    private val monday = LocalDate.of(2026, 10, 19)

    private fun at(text: String): Long =
        LocalDateTime
            .parse(text)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun utcDay(date: String): Long =
        LocalDate
            .parse(date)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    private fun meeting(
        begin: String,
        end: String,
        title: String? = "Standup",
        uid: String? = "u1",
        calendar: String = "work@example.com",
        location: String? = null,
        busyOnly: Boolean = false,
    ) = Occurrence(uid, at(begin), at(end), false, title, location, calendar, busyOnly)

    private fun allDay(
        first: String,
        after: String,
        title: String = "Holiday",
    ) = Occurrence("h1", utcDay(first), utcDay(after), true, title, null, "Personal")

    private fun daySection(
        note: String,
        heading: String,
    ): String = note.substringAfter("## $heading\n\n").substringBefore("\n## ")

    @Test
    fun `a week note is named by its ISO week`() {
        assertThat(CalendarWeeks.pathFor("Calendar", monday)).isEqualTo("Calendar/2026-W43.md")
        assertThat(CalendarWeeks.pathFor("/Calendar/", monday)).isEqualTo("Calendar/2026-W43.md")
        assertThat(CalendarWeeks.pathFor("", monday)).isEqualTo("2026-W43.md")
        // 29 December 2025 starts week 1 of 2026.
        assertThat(CalendarWeeks.pathFor("Calendar", LocalDate.of(2025, 12, 29))).isEqualTo("Calendar/2026-W01.md")
    }

    @Test
    fun `the weeks run from last week to eight weeks out`() {
        val weeks = CalendarWeeks.weeks(LocalDate.of(2026, 10, 24))
        assertThat(weeks.first()).isEqualTo(LocalDate.of(2026, 10, 12))
        assertThat(weeks).hasSize(10)
        assertThat(weeks.all { it.dayOfWeek == java.time.DayOfWeek.MONDAY }).isTrue()
    }

    @Test
    fun `every day of the week has a heading, and a free day says so`() {
        val note = CalendarWeeks.render(monday, emptyList(), zone)
        assertThat(note).startsWith("# 2026-W43\n")
        assertThat(note).contains("Times are Europe/Madrid.")
        assertThat(Regex("(?m)^## ").findAll(note).count()).isEqualTo(7)
        assertThat(daySection(note, "Sunday 25 October")).isEqualTo("No events.\n")
    }

    @Test
    fun `a meeting is listed on its day with its time, place and calendar`() {
        val note =
            CalendarWeeks.render(
                monday,
                listOf(meeting("2026-10-20T09:00", "2026-10-20T09:30", location = "Room 4")),
                zone,
            )
        assertThat(daySection(note, "Tuesday 20 October"))
            .isEqualTo("- 09:00–09:30 Standup · Room 4 (work@example.com)\n")
        assertThat(daySection(note, "Monday 19 October")).isEqualTo("No events.\n")
    }

    @Test
    fun `all-day events come first and cover each of their days`() {
        val note =
            CalendarWeeks.render(
                monday,
                listOf(meeting("2026-10-21T08:00", "2026-10-21T09:00"), allDay("2026-10-21", "2026-10-23")),
                zone,
            )
        assertThat(daySection(note, "Wednesday 21 October"))
            .isEqualTo("- All day Holiday (Personal)\n- 08:00–09:00 Standup (work@example.com)\n")
        assertThat(daySection(note, "Thursday 22 October")).isEqualTo("- All day Holiday (Personal)\n")
        assertThat(daySection(note, "Friday 23 October")).isEqualTo("No events.\n")
    }

    @Test
    fun `a meeting past midnight names the day it ends`() {
        val note = CalendarWeeks.render(monday, listOf(meeting("2026-10-23T23:00", "2026-10-24T01:00")), zone)
        assertThat(daySection(note, "Friday 23 October")).contains("23:00–Sat 01:00 Standup")
        assertThat(daySection(note, "Saturday 24 October")).isEqualTo("No events.\n")
    }

    @Test
    fun `one meeting on two company calendars is one line naming both`() {
        val events =
            CalendarWeeks.distinct(
                listOf(
                    meeting("2026-10-20T09:00", "2026-10-20T09:30", calendar = "a@one.example"),
                    meeting("2026-10-20T09:00", "2026-10-20T09:30", calendar = "b@two.example"),
                ),
            )
        assertThat(events.single().calendar).isEqualTo("a@one.example, b@two.example")
    }

    @Test
    fun `the occurrences of a repeat are kept apart`() {
        val events =
            CalendarWeeks.distinct(
                listOf(
                    meeting("2026-10-20T09:00", "2026-10-20T09:30"),
                    meeting("2026-10-21T09:00", "2026-10-21T09:30"),
                ),
            )
        assertThat(events).hasSize(2)
    }

    @Test
    fun `a busy-only calendar gives the time and nothing else`() {
        val note =
            CalendarWeeks.render(
                monday,
                listOf(
                    meeting(
                        "2026-10-20T09:00",
                        "2026-10-20T10:00",
                        title = "Acquisition",
                        location = "HQ",
                        busyOnly = true,
                    ),
                ),
                zone,
            )
        assertThat(daySection(note, "Tuesday 20 October")).isEqualTo("- 09:00–10:00 Busy (work@example.com)\n")
        assertThat(note).doesNotContain("Acquisition")
        assertThat(note).doesNotContain("HQ")
    }

    @Test
    fun `the stricter calendar wins when a meeting is on two`() {
        val events =
            CalendarWeeks.distinct(
                listOf(
                    meeting("2026-10-20T09:00", "2026-10-20T09:30", calendar = "a"),
                    meeting("2026-10-20T09:00", "2026-10-20T09:30", calendar = "b", busyOnly = true),
                ),
            )
        assertThat(events.single().busyOnly).isTrue()
    }

    @Test
    fun `a title cannot break the list or add a tag`() {
        val note =
            CalendarWeeks.render(
                monday,
                listOf(meeting("2026-10-20T09:00", "2026-10-20T09:30", title = "Plan\n#q4  review")),
                zone,
            )
        assertThat(
            daySection(note, "Tuesday 20 October"),
        ).isEqualTo("- 09:00–09:30 Plan \\#q4 review (work@example.com)\n")
    }

    @Test
    fun `an event with no title still has a line`() {
        val note =
            CalendarWeeks.render(
                monday,
                listOf(meeting("2026-10-20T09:00", "2026-10-20T09:30", title = " ")),
                zone,
            )
        assertThat(daySection(note, "Tuesday 20 October")).contains("(no title)")
    }

    @Test
    fun `the same calendar gives the same notes`() {
        val events = listOf(meeting("2026-10-20T09:00", "2026-10-20T09:30"), allDay("2026-10-21", "2026-10-22"))
        val today = LocalDate.of(2026, 10, 24)
        assertThat(CalendarWeeks.export("Calendar", today, events, zone))
            .isEqualTo(CalendarWeeks.export("Calendar", today, events.reversed(), zone))
    }

    @Test
    fun `the span covers every week with a day to spare`() {
        val weeks = CalendarWeeks.weeks(LocalDate.of(2026, 10, 24))
        val span = CalendarWeeks.span(weeks, zone)
        assertThat(span.first).isLessThan(at("2026-10-12T00:00"))
        assertThat(span.last).isAtLeast(at("2026-12-20T23:59"))
    }
}
