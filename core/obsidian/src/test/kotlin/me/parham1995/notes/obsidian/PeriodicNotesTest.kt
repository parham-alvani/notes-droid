package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Daily notes read back as a series of periods. Paths are synthetic.
 *
 * Expected names are what moment writes (English locale) for the same format
 * and date, since that is what named the file on the desktop.
 */
class PeriodicNotesTest {
    private fun notes(
        format: String,
        folder: String = "",
    ) = PeriodicNotes(DailyNotes(folder = folder, format = format))

    private val weekly = notes("YYYY-[W]ww")

    @Test
    fun `how long a period is follows from the smallest thing the format writes`() {
        assertThat(notes("YYYY-MM-DD").period).isEqualTo(Period.DAY)
        assertThat(notes("YYYY/MM/DDDD").period).isEqualTo(Period.DAY)
        assertThat(notes("gggg-[W]ww-ddd").period).isEqualTo(Period.DAY)
        assertThat(notes("dddd, MMMM Do YYYY").period).isEqualTo(Period.DAY)
        assertThat(notes("YYYY-[W]ww").period).isEqualTo(Period.WEEK)
        assertThat(notes("GGGG-[W]WW").period).isEqualTo(Period.WEEK)
        assertThat(notes("YYYY-MM").period).isEqualTo(Period.MONTH)
        assertThat(notes("MMMM YYYY").period).isEqualTo(Period.MONTH)
        assertThat(notes("YYYY-[Q]Q").period).isEqualTo(Period.QUARTER)
        assertThat(notes("YYYY").period).isEqualTo(Period.YEAR)
        // Letters inside brackets are text, not tokens: no day in "[Daily]".
        assertThat(notes("[Daily] YYYY-MM").period).isEqualTo(Period.MONTH)
    }

    @Test
    fun `a daily note is the day it names`() {
        val daily = notes("YYYY-MM-DD")
        assertThat(daily.periodOf("2026-03-04.md")).isEqualTo(LocalDate.of(2026, 3, 4))
        assertThat(daily.previous("2026-03-01.md")).isEqualTo("2026-02-28.md")
        assertThat(daily.next("2026-12-31.md")).isEqualTo("2027-01-01.md")
    }

    @Test
    fun `what moment would not have written is an ordinary note`() {
        val daily = notes("YYYY-MM-DD", folder = "Journal")
        assertThat(daily.periodOf("Journal/2026-03-04.md")).isEqualTo(LocalDate.of(2026, 3, 4))
        assertThat(daily.periodOf("2026-03-04.md")).isNull() // outside the folder
        assertThat(daily.periodOf("Other/2026-03-04.md")).isNull()
        assertThat(daily.periodOf("Journal/2026-02-30.md")).isNull()
        assertThat(daily.periodOf("Journal/2026-13-01.md")).isNull()
        assertThat(daily.periodOf("Journal/2026-3-4.md")).isNull()
        assertThat(daily.periodOf("Journal/2026-03-04 notes.md")).isNull()
        assertThat(daily.periodOf("Journal/2026-03-04.pdf")).isNull()
        assertThat(daily.periodOf("Journal/Plan.md")).isNull()
        assertThat(daily.previous("Journal/Plan.md")).isNull()
        assertThat(daily.next("Journal/Plan.md")).isNull()
    }

    @Test
    fun `names, weekdays and folders of the format's own are read back`() {
        val daily = notes("YYYY/MM-MMMM/YYYY-MM-DD dddd", folder = "Journal/")
        val path = "Journal/2026/03-March/2026-03-04 Wednesday.md"
        assertThat(daily.periodOf(path)).isEqualTo(LocalDate.of(2026, 3, 4))
        assertThat(daily.next(path)).isEqualTo("Journal/2026/03-March/2026-03-05 Thursday.md")
        assertThat(daily.previous("Journal/2026/03-March/2026-03-01 Sunday.md"))
            .isEqualTo("Journal/2026/02-February/2026-02-28 Saturday.md")
        // The right date under the wrong weekday is not a name moment writes.
        assertThat(daily.periodOf("Journal/2026/03-March/2026-03-04 Friday.md")).isNull()

        val spelled = notes("dddd, MMMM Do YYYY")
        assertThat(spelled.periodOf("Wednesday, March 4th 2026.md")).isEqualTo(LocalDate.of(2026, 3, 4))
    }

    @Test
    fun `a weekly note is the week it names, starting on sunday`() {
        // 24 September 2026 is a Thursday. Moment's English week 1 is the week
        // holding 1 January -- 28 December 2025 to 3 January 2026 -- and the
        // Sunday of the 24th's week, the 20th, is 266 days after that
        // Sunday: 38 weeks on, so week 39.
        assertThat(MomentFormat.format("YYYY-[W]ww", LocalDate.of(2026, 9, 24))).isEqualTo("2026-W39")
        assertThat(weekly.periodOf("2026-W39.md")).isEqualTo(LocalDate.of(2026, 9, 20))
        assertThat(weekly.previous("2026-W39.md")).isEqualTo("2026-W38.md")
        assertThat(weekly.next("2026-W39.md")).isEqualTo("2026-W40.md")
        assertThat(weekly.periodOf("2026-W60.md")).isNull()
        assertThat(weekly.periodOf("2026-W00.md")).isNull()
    }

    /**
     * The year boundary under `YYYY-[W]ww`, which is the format this is for.
     *
     * Moment writes `YYYY` as the calendar year but `ww` as the week of the
     * locale's week-year, whose week 1 is the Sunday-to-Saturday week holding
     * 1 January. 1 January 2027 is a Friday, so that week runs from Sunday 27
     * December 2026 to Saturday 2 January 2027, and moment writes its days as:
     *
     * - 26 December 2026 (Saturday), the week before: `2026-W52`
     * - 27--31 December 2026: week 1, calendar year 2026, so `2026-W01`
     * - 1--2 January 2027: week 1, calendar year 2027, so `2027-W01`
     *
     * So `2026-W01` is written both by 1--3 January 2026 (week 1 of 2026,
     * which began on 28 December 2025) and by 27--31 December 2026. It is read
     * as the first of those, and stepping forward from week 52 of 2026 goes to
     * `2027-W01` -- the name that reads back as that week -- rather than a
     * year backwards.
     */
    @Test
    fun `the week that straddles new year is named the way moment names it`() {
        fun name(date: LocalDate) = MomentFormat.format("YYYY-[W]ww", date)
        assertThat(name(LocalDate.of(2026, 12, 26))).isEqualTo("2026-W52")
        assertThat(name(LocalDate.of(2026, 12, 27))).isEqualTo("2026-W01")
        assertThat(name(LocalDate.of(2026, 12, 31))).isEqualTo("2026-W01")
        assertThat(name(LocalDate.of(2027, 1, 1))).isEqualTo("2027-W01")
        assertThat(name(LocalDate.of(2026, 1, 1))).isEqualTo("2026-W01")

        assertThat(weekly.periodOf("2026-W01.md")).isEqualTo(LocalDate.of(2025, 12, 28))
        assertThat(weekly.periodOf("2027-W01.md")).isEqualTo(LocalDate.of(2026, 12, 27))
        assertThat(weekly.periodOf("2026-W52.md")).isEqualTo(LocalDate.of(2026, 12, 20))

        assertThat(weekly.next("2026-W52.md")).isEqualTo("2027-W01.md")
        assertThat(weekly.previous("2027-W01.md")).isEqualTo("2026-W52.md")
        assertThat(weekly.next("2027-W01.md")).isEqualTo("2027-W02.md")
        // 1 January 2025 is a Wednesday, so 2025's week 1 began on 29
        // December 2024 and 21 December 2025 is 51 weeks after it.
        assertThat(weekly.previous("2026-W01.md")).isEqualTo("2025-W52.md")
    }

    @Test
    fun `iso weeks start on monday and 2026 has 53 of them`() {
        // 1 January 2027 is a Friday, so it belongs to the ISO week that holds
        // Thursday 31 December: 2026's 53rd. ISO week 1 of 2027 starts on
        // Monday 4 January.
        val iso = notes("GGGG-[W]WW")
        assertThat(iso.periodOf("2026-W53.md")).isEqualTo(LocalDate.of(2026, 12, 28))
        assertThat(iso.next("2026-W53.md")).isEqualTo("2027-W01.md")
        assertThat(iso.periodOf("2027-W01.md")).isEqualTo(LocalDate.of(2027, 1, 4))
        assertThat(iso.previous("2027-W01.md")).isEqualTo("2026-W53.md")
        // 2027 has 52, so a 53rd is not a week moment could name.
        assertThat(iso.periodOf("2027-W53.md")).isNull()
    }

    @Test
    fun `months, quarters and years step by their own length`() {
        val monthly = notes("YYYY-MM", folder = "Months")
        assertThat(monthly.periodOf("Months/2026-12.md")).isEqualTo(LocalDate.of(2026, 12, 1))
        assertThat(monthly.next("Months/2026-12.md")).isEqualTo("Months/2027-01.md")
        assertThat(monthly.previous("Months/2026-01.md")).isEqualTo("Months/2025-12.md")

        val named = notes("MMMM YYYY")
        assertThat(named.next("December 2026.md")).isEqualTo("January 2027.md")

        val quarterly = notes("YYYY-[Q]Q")
        assertThat(quarterly.periodOf("2026-Q3.md")).isEqualTo(LocalDate.of(2026, 7, 1))
        assertThat(quarterly.next("2026-Q4.md")).isEqualTo("2027-Q1.md")
        assertThat(quarterly.periodOf("2026-Q5.md")).isNull()

        val yearly = notes("YYYY")
        assertThat(yearly.previous("2026.md")).isEqualTo("2025.md")
        assertThat(yearly.periodOf("Plan.md")).isNull()
    }

    @Test
    fun `the period a date falls in`() {
        val thursday = LocalDate.of(2026, 9, 24)
        assertThat(weekly.startOf(thursday)).isEqualTo(LocalDate.of(2026, 9, 20))
        assertThat(notes("GGGG-[W]WW").startOf(thursday)).isEqualTo(LocalDate.of(2026, 9, 21))
        assertThat(notes("YYYY-MM").startOf(thursday)).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(notes("YYYY-[Q]Q").startOf(thursday)).isEqualTo(LocalDate.of(2026, 7, 1))
        assertThat(notes("YYYY").startOf(thursday)).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(weekly.pathOf(weekly.startOf(thursday))).isEqualTo("2026-W39.md")
    }

    @Test
    fun `the nearest note in a direction skips the periods that have none`() {
        val paths = listOf("2026-W30.md", "2026-W35.md", "2026-W39.md", "2026-W44.md", "Plan.md", "2026-W99.md")
        assertThat(weekly.nearest(paths, "2026-W39.md", -1)).isEqualTo("2026-W35.md")
        assertThat(weekly.nearest(paths, "2026-W39.md", 1)).isEqualTo("2026-W44.md")
        assertThat(weekly.nearest(paths, "2026-W30.md", -1)).isNull()
        assertThat(weekly.nearest(paths, "2026-W44.md", 1)).isNull()
        // Across New Year, by the period and not by the name's order.
        val boundary = listOf("2026-W01.md", "2026-W52.md", "2027-W01.md", "2027-W02.md")
        assertThat(weekly.nearest(boundary, "2026-W52.md", 1)).isEqualTo("2027-W01.md")
        assertThat(weekly.nearest(boundary, "2027-W01.md", -1)).isEqualTo("2026-W52.md")
        // From a note that is not in the series there is no direction at all.
        assertThat(weekly.nearest(paths, "Plan.md", 1)).isNull()
    }
}
