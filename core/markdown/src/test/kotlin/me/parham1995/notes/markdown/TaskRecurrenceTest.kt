package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class TaskRecurrenceTest {
    @Test
    fun `reads the plain intervals`() {
        assertThat(TaskRecurrence.parse("every day"))
            .isEqualTo(Recurrence(1, RecurrenceUnit.DAY, whenDone = false))
        assertThat(TaskRecurrence.parse("every 2 weeks"))
            .isEqualTo(Recurrence(2, RecurrenceUnit.WEEK, whenDone = false))
        assertThat(TaskRecurrence.parse("every month"))
            .isEqualTo(Recurrence(1, RecurrenceUnit.MONTH, whenDone = false))
        assertThat(TaskRecurrence.parse("every 3 years"))
            .isEqualTo(Recurrence(3, RecurrenceUnit.YEAR, whenDone = false))
    }

    @Test
    fun `reads when done`() {
        assertThat(TaskRecurrence.parse("every week when done"))
            .isEqualTo(Recurrence(1, RecurrenceUnit.WEEK, whenDone = true))
    }

    @Test
    fun `refuses anything it would have to guess at`() {
        assertThat(TaskRecurrence.parse("every week on Sunday")).isNull()
        assertThat(TaskRecurrence.parse("every weekday")).isNull()
        assertThat(TaskRecurrence.parse("every month on the 1st")).isNull()
        assertThat(TaskRecurrence.parse("daily")).isNull()
        assertThat(TaskRecurrence.parse("")).isNull()
        assertThat(TaskRecurrence.parse("every 0 days")).isNull()
    }

    @Test
    fun `steps once when that is already in the future`() {
        val rule = Recurrence(1, RecurrenceUnit.WEEK, whenDone = false)

        assertThat(TaskRecurrence.next(LocalDate.of(2026, 9, 19), rule, LocalDate.of(2026, 9, 20)))
            .isEqualTo(LocalDate.of(2026, 9, 26))
    }

    @Test
    fun `keeps stepping past a task left for weeks`() {
        val rule = Recurrence(1, RecurrenceUnit.DAY, whenDone = false)

        // Ten days overdue: one step would land yesterday and be overdue again
        // the moment it is written.
        assertThat(TaskRecurrence.next(LocalDate.of(2026, 9, 10), rule, LocalDate.of(2026, 9, 20)))
            .isEqualTo(LocalDate.of(2026, 9, 21))
    }

    @Test
    fun `a monthly rule lands on the same day of the month`() {
        val rule = Recurrence(1, RecurrenceUnit.MONTH, whenDone = false)

        assertThat(TaskRecurrence.next(LocalDate.of(2026, 1, 31), rule, LocalDate.of(2026, 1, 31)))
            .isEqualTo(LocalDate.of(2026, 2, 28))
    }

    @Test
    fun `an interval past the end of the calendar is not a rule worked out`() {
        assertThat(TaskRecurrence.parse("every 99999999999 days")).isNull()
        assertThat(TaskRecurrence.parse("every 999999999999999999999 years")).isNull()
        assertThat(
            TaskLine.completeRecurring("- [ ] far 🔁 every 99999999999 days 📅 2026-10-26", "2026-10-26"),
        ).isNull()

        val far = TaskRecurrence.parse("every 10000 years")!!
        assertThat(TaskRecurrence.next(LocalDate.of(2026, 1, 1), far, LocalDate.of(2026, 1, 1)).year).isEqualTo(12026)
    }
}
