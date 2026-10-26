package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class PostponeTest {
    private val today = LocalDate.of(2026, 10, 26)

    @Test
    fun `a date still ahead moves on from itself`() {
        val due = LocalDate.of(2026, 11, 2)

        assertThat(Postpone.DAY.from(due, today)).isEqualTo(LocalDate.of(2026, 11, 3))
        assertThat(Postpone.THREE_DAYS.from(due, today)).isEqualTo(LocalDate.of(2026, 11, 5))
        assertThat(Postpone.TWO_WEEKS.from(due, today)).isEqualTo(LocalDate.of(2026, 11, 16))
    }

    @Test
    fun `a date past or today moves on from today`() {
        assertThat(Postpone.DAY.from(LocalDate.of(2026, 9, 1), today)).isEqualTo(LocalDate.of(2026, 10, 27))
        assertThat(Postpone.TWO_DAYS.from(today, today)).isEqualTo(LocalDate.of(2026, 10, 28))
        assertThat(Postpone.WEEK.from(LocalDate.of(2020, 1, 1), today)).isEqualTo(LocalDate.of(2026, 11, 2))
    }

    @Test
    fun `a month is a calendar month, short months clamped`() {
        assertThat(Postpone.MONTH.from(LocalDate.of(2027, 1, 31), today)).isEqualTo(LocalDate.of(2027, 2, 28))
    }
}
