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
    fun `a task ticked years late comes back after today`() {
        val next = { line: String, today: String -> TaskLine.completeRecurring(line, today)!!.first() }

        assertThat(next("- [ ] Water 🔁 every day 📅 1990-01-01", "2026-11-06"))
            .isEqualTo("- [ ] Water 🔁 every day 📅 2026-11-07")
        assertThat(next("- [ ] Bins 🔁 every 2 weeks 📅 1990-01-01", "2026-11-06"))
            .isEqualTo("- [ ] Bins 🔁 every 2 weeks 📅 2026-11-16")
        assertThat(next("- [ ] Rent 🔁 every month 📅 1990-01-31", "2026-11-06"))
            .isEqualTo("- [ ] Rent 🔁 every month 📅 2026-11-28")
        assertThat(next("- [ ] Early 🔁 every day 📅 2026-12-01", "2026-11-06"))
            .isEqualTo("- [ ] Early 🔁 every day 📅 2026-12-02")
    }

    @Test
    fun `months too many steps behind have no next occurrence rather than an overdue one`() {
        val monthly = Recurrence(1, RecurrenceUnit.MONTH, whenDone = false)

        // 4,000 steps from 1600 reach 1933, still before today.
        assertThat(TaskRecurrence.next(LocalDate.of(1600, 1, 1), monthly, LocalDate.of(2026, 11, 6))).isNull()
        assertThat(TaskLine.completeRecurring("- [ ] Old 🔁 every month 📅 1600-01-01", "2026-11-06"))
            .containsExactly("- [x] Old 🔁 every month 📅 1600-01-01 ✅ 2026-11-06")
    }

    @Test
    fun `a monthly rule lands on the same day of the month`() {
        val rule = Recurrence(1, RecurrenceUnit.MONTH, whenDone = false)

        assertThat(TaskRecurrence.next(LocalDate.of(2026, 1, 31), rule, LocalDate.of(2026, 1, 31)))
            .isEqualTo(LocalDate.of(2026, 2, 28))
    }

    @Test
    fun `a number over the limit is refused`() {
        assertThat(TaskRecurrence.parse("every 10000 years")?.amount).isEqualTo(10_000L)
        listOf(
            "every 10001 days",
            "every 999999999 years",
            "every 4000000000 days",
            "every 99999999999 days",
            "every 99999999999999999999 weeks",
            "every 999999999999999999999 years",
        ).forEach { assertThat(TaskRecurrence.parse(it)).isNull() }
        // Ticked with no next occurrence, never a crash.
        assertThat(TaskLine.completeRecurring("- [ ] Far 🔁 every 999999999 years 📅 2026-01-01", "2026-01-01"))
            .containsExactly("- [x] Far 🔁 every 999999999 years 📅 2026-01-01 ✅ 2026-01-01")
        assertThat(VaultEdits.setStatus(0, "Far", 'x', "2026-01-01")("- [ ] Far 🔁 every 999999999 years 📅 2026-01-01"))
            .isEqualTo("- [x] Far 🔁 every 999999999 years 📅 2026-01-01 ✅ 2026-01-01")
        assertThat(TaskLine.completeRecurring("- [ ] Far 🔁 every 10000 years 📅 2026-01-01", "2026-01-01")!!.first())
            .isEqualTo("- [ ] Far 🔁 every 10000 years 📅 12026-01-01")

        val far = TaskRecurrence.parse("every 10000 years")!!
        assertThat(TaskRecurrence.next(LocalDate.of(2026, 1, 1), far, LocalDate.of(2026, 1, 1))?.year).isEqualTo(12026)
    }

    @Test
    fun `reads the jalali months and years`() {
        assertThat(TaskRecurrence.parse("every jalali month"))
            .isEqualTo(Recurrence(1, RecurrenceUnit.JALALI_MONTH, whenDone = false))
        assertThat(TaskRecurrence.parse("Every 3 Jalali Months when done"))
            .isEqualTo(Recurrence(3, RecurrenceUnit.JALALI_MONTH, whenDone = true))
        assertThat(TaskRecurrence.parse("every jalali year"))
            .isEqualTo(Recurrence(1, RecurrenceUnit.JALALI_YEAR, whenDone = false))
        assertThat(TaskRecurrence.parse("every 2 jalali years"))
            .isEqualTo(Recurrence(2, RecurrenceUnit.JALALI_YEAR, whenDone = false))
        assertThat(TaskRecurrence.parse("every  2   jalali   months"))
            .isEqualTo(Recurrence(2, RecurrenceUnit.JALALI_MONTH, whenDone = false))
        assertThat(TaskRecurrence.parse("every month"))
            .isEqualTo(Recurrence(1, RecurrenceUnit.MONTH, whenDone = false))
    }

    @Test
    fun `refuses a jalali rule it would have to guess at`() {
        listOf(
            "every jalali week",
            "every jalali day",
            "every jalali",
            "every 0 jalali months",
            "every jalali month on the 10th",
            "every persian month",
            "every 10001 jalali months",
        ).forEach { assertThat(TaskRecurrence.parse(it)).isNull() }
    }

    @Test
    fun `a jalali month keeps the day of the jalali month`() {
        val rule = Recurrence(1, RecurrenceUnit.JALALI_MONTH, whenDone = false)

        // Dey 10 to Bahman 10; every month would give 2027-01-31.
        assertThat(TaskRecurrence.next(LocalDate.of(2026, 12, 31), rule, LocalDate.of(2026, 12, 31)))
            .isEqualTo(LocalDate.of(2027, 1, 30))
    }

    @Test
    fun `a step off the jalali calendar is no next day, not a crash`() {
        val far = TaskRecurrence.parse("every 9000 jalali years")!!

        assertThat(TaskRecurrence.next(LocalDate.of(2026, 12, 31), far, LocalDate.of(2026, 12, 31))).isNull()
    }
}
