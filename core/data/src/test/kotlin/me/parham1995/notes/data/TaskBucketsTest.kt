package me.parham1995.notes.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class TaskBucketsTest {
    private val today = LocalDate.of(2026, 9, 18)

    private fun bucket(date: String?) = TaskBuckets.of(date, today)

    @Test
    fun `yesterday is overdue and today is today`() {
        assertThat(bucket("2026-09-17")).isEqualTo(TaskBucket.OVERDUE)
        assertThat(bucket("2026-09-18")).isEqualTo(TaskBucket.TODAY)
    }

    @Test
    fun `the week runs to the seventh day, not the eighth`() {
        assertThat(bucket("2026-09-19")).isEqualTo(TaskBucket.TOMORROW)
        assertThat(bucket("2026-09-20")).isEqualTo(TaskBucket.THIS_WEEK)
        assertThat(bucket("2026-09-24")).isEqualTo(TaskBucket.THIS_WEEK)
        assertThat(bucket("2026-09-25")).isEqualTo(TaskBucket.LATER)
    }

    @Test
    fun `a task with no date sorts last rather than disappearing`() {
        assertThat(bucket(null)).isEqualTo(TaskBucket.UNDATED)
    }

    @Test
    fun `a date the plugin wrote in some other shape does not lose the task`() {
        assertThat(bucket("next tuesday")).isEqualTo(TaskBucket.UNDATED)
        assertThat(bucket("2026-13-45")).isEqualTo(TaskBucket.UNDATED)
        assertThat(bucket("")).isEqualTo(TaskBucket.UNDATED)
    }

    @Test
    fun `a long-overdue task is still just overdue`() {
        // 120 of these in the vault this was built for. One bucket, not a
        // gradient -- the point of the list is what to do, not how bad it is.
        assertThat(bucket("2024-01-01")).isEqualTo(TaskBucket.OVERDUE)
    }

    @Test
    fun `the buckets are declared in the order they are shown`() {
        assertThat(TaskBucket.entries.map { it.label })
            .containsExactly("Overdue", "Today", "Tomorrow", "This week", "Later", "No date")
            .inOrder()
    }
}
