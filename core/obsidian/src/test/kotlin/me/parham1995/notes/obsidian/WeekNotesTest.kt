package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Every fixture here is made up. The repository is public; the vault is not. */
class WeekNotesTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val monday = LocalDate.of(2026, 10, 19)

    private fun at(text: String): Long =
        LocalDateTime
            .parse(text)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `a week note the phone wrote reads back as the meetings it was written from`() {
        val events =
            listOf(
                Occurrence(
                    "a",
                    at("2026-10-20T09:00"),
                    at("2026-10-20T10:00"),
                    false,
                    "Standup *daily*",
                    null,
                    "work@example.com",
                ),
                Occurrence(
                    "b",
                    at("2026-10-20T09:30"),
                    at("2026-10-20T11:00"),
                    false,
                    "Review",
                    null,
                    "Work",
                    conference = "https://meet.google.com/abc-defg-hij",
                    tentative = true,
                ),
                Occurrence("c", at("2026-10-20T23:00"), at("2026-10-21T01:00"), false, "Late call", null, "Work"),
                Occurrence(
                    "d",
                    LocalDate
                        .of(2026, 10, 22)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli(),
                    LocalDate
                        .of(2026, 10, 23)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli(),
                    true,
                    "Holiday",
                    null,
                    "Personal",
                ),
                Occurrence(
                    "e",
                    at("2026-10-23T14:00"),
                    at("2026-10-23T15:00"),
                    false,
                    null,
                    null,
                    "Other",
                    busyOnly = true,
                ),
            )
        val text = CalendarWeeks.render(monday, events, zone)

        val days = WeekNotes.parse(text, "Calendar/2026-W43.md", ZoneOffset.UTC)

        assertThat(days.map { it.date }).containsExactlyElementsIn((0L until 7L).map { monday.plusDays(it) }).inOrder()
        val tuesday = days[1]
        assertThat(
            tuesday.meetings.map { it.title },
        ).containsExactly("Standup *daily*", "Review", "Late call").inOrder()
        assertThat(tuesday.free).isEqualTo("11:00–18:00")
        val standup = tuesday.meetings[0]
        // The zone is the note's own, not the one asked to fall back on.
        assertThat(standup.start).isEqualTo(at("2026-10-20T09:00"))
        assertThat(standup.calendars).containsExactly("work@example.com")
        assertThat(standup.overlaps).isEqualTo("09:30 Review")
        val review = tuesday.meetings[1]
        assertThat(review.tentative).isTrue()
        assertThat(review.join).isEqualTo("https://meet.google.com/abc-defg-hij")
        assertThat(tuesday.meetings[2].end).isEqualTo(at("2026-10-21T01:00"))
        assertThat(days[3].meetings.single().allDay).isTrue()
        assertThat(days[3].free).isEqualTo("none")
        assertThat(days[4].meetings.single().busyOnly).isTrue()
        assertThat(days[0].meetings).isEmpty()
    }

    @Test
    fun `a week across the new year puts each day in its own year`() {
        val text = CalendarWeeks.render(LocalDate.of(2025, 12, 29), emptyList(), zone)
        val days = WeekNotes.parse(text, "Calendar/2026-W01.md")
        assertThat(days.first().date).isEqualTo(LocalDate.of(2025, 12, 29))
        assertThat(days.last().date).isEqualTo(LocalDate.of(2026, 1, 4))
    }

    @Test
    fun `a note that is not a week note is nothing`() {
        assertThat(WeekNotes.parse("## Monday 19 October\n", "Calendar/Notes.md")).isEmpty()
    }
}
