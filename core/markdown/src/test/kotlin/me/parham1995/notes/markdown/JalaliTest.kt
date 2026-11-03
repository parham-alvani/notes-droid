package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/** The cases of `jalali-recurrence.md` and Daftar for Linux's `jalali.rs`. */
class JalaliTest {
    private fun day(
        y: Int,
        m: Int,
        d: Int,
    ): LocalDate = LocalDate.of(y, m, d)

    @Test
    fun `nowruz and the months fall where the calendar puts them`() {
        assertThat(Jalali.of(day(2026, 3, 21))).isEqualTo(Jalali(1405, 1, 1))
        assertThat(Jalali.of(day(2024, 3, 20))).isEqualTo(Jalali(1403, 1, 1))
        assertThat(Jalali.of(day(2026, 10, 27))).isEqualTo(Jalali(1405, 8, 5))
        assertThat(Jalali.of(day(2025, 3, 20))).isEqualTo(Jalali(1403, 12, 30))
        assertThat(Jalali.of(day(1979, 2, 11))).isEqualTo(Jalali(1357, 11, 22))
    }

    @Test
    fun `a day goes there and back`() {
        var at = day(1990, 1, 1)
        repeat(20_000) {
            assertThat(Jalali.of(at).toDate()).isEqualTo(at)
            at = at.plusDays(3)
        }
        assertThat(Jalali(1404, 12, 30).toDate()).isNull()
        assertThat(Jalali(1403, 12, 30).toDate()).isEqualTo(day(2025, 3, 20))
    }

    @Test
    fun `a month on keeps the day or the last one`() {
        // Dey 10 to Bahman 10, a day before the Gregorian month would go.
        assertThat(Jalali.addingMonths(day(2026, 12, 31), 1)).isEqualTo(day(2027, 1, 30))
        // Shahrivar 31 to Mehr 30.
        assertThat(Jalali.addingMonths(day(2026, 9, 22), 1)).isEqualTo(day(2026, 10, 22))
        // 1403-12-30, a leap Esfand, to 1404-12-29.
        assertThat(Jalali.addingMonths(day(2025, 3, 20), 12)).isEqualTo(day(2026, 3, 20))
        // Dey 10 1405 to Farvardin 10 1406, over the year's end.
        assertThat(Jalali.addingMonths(day(2026, 12, 31), 3)).isEqualTo(day(2027, 3, 30))
        assertThat(Jalali.addingMonths(day(2026, 12, 31), -1)).isEqualTo(day(2026, 12, 1))
        assertThat(Jalali.addingMonths(day(2026, 12, 31), 12L * 5000)).isNull()
        assertThat(Jalali.addingMonths(day(2026, 12, 31), Long.MAX_VALUE)).isNull()
        assertThat(Jalali.addingMonths(day(2026, 12, 31), Long.MIN_VALUE)).isNull()
        assertThat(Jalali.monthLength(1403, 12)).isEqualTo(30)
        assertThat(Jalali.monthLength(1404, 12)).isEqualTo(29)
        assertThat(Jalali.monthLength(1404, 13)).isNull()
    }

    @Test
    fun `it reads as persian`() {
        assertThat(Jalali.long(day(2026, 10, 27))).isEqualTo("سه‌شنبه ۵ آبان ۱۴۰۵")
        assertThat(Jalali.persianDigits("2026-10")).isEqualTo("۲۰۲۶-۱۰")
        assertThat(Jalali.latinDigits("۱۴۰۵/۰۸/۰۵")).isEqualTo("1405/08/05")
        assertThat(Jalali.latinDigits("٢٠٢٦")).isEqualTo("2026")
    }

    @Test
    fun `a jalali day typed is its gregorian day`() {
        assertThat(Jalali.parse("1405-08-05")).isEqualTo(day(2026, 10, 27))
        assertThat(Jalali.parse("۱۴۰۵/۸/۵")).isEqualTo(day(2026, 10, 27))
        assertThat(Jalali.parse("2026-10-27")).isNull()
        assertThat(Jalali.parse("1405-13-01")).isNull()
        assertThat(Jalali.of(day(2026, 10, 27)).toIso()).isEqualTo("1405-08-05")
    }
}
