package me.parham1995.notes.data

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.database.TaskRow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the digest's notifications say, read back off the notifications
 * built. The extras are what the shade renders, so a line missing here is a
 * line missing on the lock screen.
 */
@RunWith(RobolectricTestRunner::class)
class TaskDigestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun task(
        text: String,
        note: String = "Apollo",
    ) = TaskRow(
        id = 0,
        noteId = 0,
        text = text,
        state = "UNCHECKED",
        section = "",
        blockIndex = 0,
        line = 0,
        actionableOn = "2026-09-23",
        recurring = null,
        notePath = "Work/$note.md",
        noteTitle = note,
        vaultId = 1,
    )

    @Test
    fun `names each task due today with the note it is in`() {
        val notification =
            dueTodayNotification(context, due = listOf(task("call the bank"), task("water", "Plants")), open = null)

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Tasks")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("2 due today")
        assertThat(lines(notification)).containsExactly("call the bank · Apollo", "water · Plants").inOrder()
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)).isNull()
    }

    @Test
    fun `a long day is cut short and says by how much`() {
        val due = (1..7).map { task("task $it") }

        val notification = dueTodayNotification(context, due = due, open = null)

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("7 due today")
        assertThat(lines(notification)).hasSize(TaskDigestWorker.MAX_LINES)
        assertThat(lines(notification).last()).isEqualTo("task 5 · Apollo")
        assertThat(
            notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT).toString(),
        ).isEqualTo("and 2 more")
    }

    @Test
    fun `overdue is a count and no list`() {
        val notification = overdueNotification(context, overdue = 1, open = null)

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Tasks")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).isEqualTo("1 overdue")
        assertThat(notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)).isNull()
    }

    @Test
    fun `each question has its own channel, so either can be silenced alone`() {
        val due = dueTodayNotification(context, due = listOf(task("one")), open = null)
        val overdue = overdueNotification(context, overdue = 4, open = null)

        assertThat(due.channelId).isEqualTo(TaskDigestWorker.DUE_CHANNEL_ID)
        assertThat(overdue.channelId).isEqualTo(TaskDigestWorker.OVERDUE_CHANNEL_ID)
        assertThat(due.channelId).isNotEqualTo(overdue.channelId)
        assertThat(TaskDigestWorker.DUE_NOTIFICATION_ID).isNotEqualTo(TaskDigestWorker.OVERDUE_NOTIFICATION_ID)
    }

    private fun lines(notification: Notification): List<String> =
        notification.extras
            .getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            .orEmpty()
            .map { it.toString() }
}
