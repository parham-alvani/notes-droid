package me.parham1995.notes.data

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.obsidian.NotificationRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

/** What a notification rule's alert says, read back off the notification built. */
@RunWith(RobolectricTestRunner::class)
class NotificationRuleAlertTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rule = NotificationRule("diary", "Diet/Diary.md", "D MMM YYYY · dddd", LocalTime.of(21, 0))
    private val day = LocalDate.of(2026, 11, 25)

    @Test
    fun `the alert names the note and the heading it lacks, on the rules' own channel`() {
        val notification = ruleNotification(context, rule, day, open = null)

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Diary")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("No heading for today yet: 25 Nov 2026 · Wednesday")
        assertThat(notification.channelId).isEqualTo(NotificationRuleWorker.CHANNEL_ID)
        assertThat(NotificationRuleWorker.tagOf(rule)).isEqualTo("rule.diary")
    }

    @Test
    fun `a rule's own title and message are what the alert says`() {
        val said = rule.copy(title = "Food", message = "Nothing is logged in the diary today.")

        val notification = ruleNotification(context, said, day, open = null)

        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("Food")
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("Nothing is logged in the diary today.")
    }
}
