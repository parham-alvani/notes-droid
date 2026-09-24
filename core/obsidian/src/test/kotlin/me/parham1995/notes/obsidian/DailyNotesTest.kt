package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Daily note paths, and the moment.js formats that name them.
 *
 * Expected values are what moment itself writes for the same format and date
 * (English locale), since that is what named the file on the desktop.
 */
class DailyNotesTest {
    /** A Wednesday, in the first week of March. */
    private val date = LocalDate.of(2026, 3, 4)

    private fun format(pattern: String) = MomentFormat.format(pattern, date)

    @Test
    fun `the default format is an ISO date`() {
        assertThat(format("YYYY-MM-DD")).isEqualTo("2026-03-04")
        assertThat(DailyNotes().pathFor(date)).isEqualTo("2026-03-04.md")
    }

    @Test
    fun `numbers pad as moment pads them`() {
        assertThat(format("YY.M.D")).isEqualTo("26.3.4")
        assertThat(format("DD/MM/YYYY")).isEqualTo("04/03/2026")
        assertThat(format("DDD DDDD")).isEqualTo("63 063")
        assertThat(format("Q")).isEqualTo("1")
    }

    @Test
    fun `names of months and days`() {
        assertThat(format("dddd, MMMM Do YYYY")).isEqualTo("Wednesday, March 4th 2026")
        assertThat(format("ddd D MMM")).isEqualTo("Wed 4 Mar")
        assertThat(format("dd")).isEqualTo("We")
        assertThat(format("d E")).isEqualTo("3 3")
    }

    @Test
    fun `ordinals`() {
        fun day(n: Int) = MomentFormat.format("Do", LocalDate.of(2026, 1, n))
        assertThat(listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map(::day))
            .containsExactly("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st")
            .inOrder()
    }

    @Test
    fun `sunday is the first day of the week to moment`() {
        assertThat(MomentFormat.format("d dddd", LocalDate.of(2026, 3, 1))).isEqualTo("0 Sunday")
        assertThat(MomentFormat.format("E", LocalDate.of(2026, 3, 1))).isEqualTo("7")
    }

    @Test
    fun `weeks, both kinds`() {
        // 1 January 2027 is a Friday: moment's English week 1 holds it, while
        // ISO week 1 is the following one and the Friday belongs to 2026's 53rd.
        val newYear = LocalDate.of(2027, 1, 1)
        assertThat(MomentFormat.format("gggg-[W]ww", newYear)).isEqualTo("2027-W01")
        assertThat(MomentFormat.format("GGGG-[W]WW", newYear)).isEqualTo("2026-W53")
    }

    @Test
    fun `brackets and backslashes are literal`() {
        assertThat(format("[Day] D [of] MMMM")).isEqualTo("Day 4 of March")
        assertThat(format("YYYY[-DD]")).isEqualTo("2026-DD")
        assertThat(format("\\DD")).isEqualTo("D4")
        // A letter moment does not know is itself.
        assertThat(format("YYYY_MM_DD (x)")).isEqualTo("2026_03_04 (x)")
    }

    @Test
    fun `a format can put the note in folders of its own`() {
        val notes = DailyNotes(folder = "Journal/", format = "YYYY/MM-MMMM/YYYY-MM-DD dddd")
        assertThat(notes.pathFor(date)).isEqualTo("Journal/2026/03-March/2026-03-04 Wednesday.md")
    }

    @Test
    fun `the settings file is read, and what is left out is the default`() {
        assertThat(DailyNotes.parse("""{"folder":"Daily","format":"DD-MM-YYYY","template":"T/Day"}"""))
            .isEqualTo(DailyNotes("Daily", "DD-MM-YYYY"))
        assertThat(DailyNotes.parse("""{"folder":"","format":""}""")).isEqualTo(DailyNotes())
        assertThat(DailyNotes.parse("""{"autorun":true}""")).isEqualTo(DailyNotes())
        assertThat(DailyNotes.parse("""{"folder":"/Daily/"}""").pathFor(date)).isEqualTo("Daily/2026-03-04.md")
    }
}
