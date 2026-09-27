package me.parham1995.notes.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reminders kept on the phone, and what survives a restart. Synthetic. */
@RunWith(RobolectricTestRunner::class)
class TaskRemindersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun reminder(
        at: Long,
        text: String = "water the tomatoes",
    ) = TaskReminder(0, 1L, "Garden/Plan.md", "Plan", text, 4, at)

    @Test
    fun `reminders come back after a restart, soonest first, with ids of their own`() {
        val first = TaskReminders(context)
        val late = first.add(reminder(at = 2_000))
        val early = first.add(reminder(at = 1_000, text = "buy seeds | and pots"))

        val again = TaskReminders(context)

        assertThat(
            again.all.value.map { it.text },
        ).containsExactly("buy seeds | and pots", "water the tomatoes").inOrder()
        assertThat(late.id).isNotEqualTo(early.id)
        assertThat(again.byId(late.id)).isEqualTo(late)
    }

    @Test
    fun `a removed reminder stays removed`() {
        val store = TaskReminders(context)
        val kept = store.add(reminder(at = 1_000))
        store.remove(kept.id)

        assertThat(TaskReminders(context).all.value).isEmpty()
    }

    @Test
    fun `a damaged entry does not take the others with it`() {
        val good = TaskReminders.encode(listOf(reminder(at = 1).copy(id = 1)))
        val damaged = good.dropLast(1) + ",{\"id\":2}]"

        assertThat(TaskReminders.decode(damaged).map { it.id }).containsExactly(1L)
        assertThat(TaskReminders.decode("not json")).isEmpty()
    }
}
