package me.parham1995.notes.navigation

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.TaskDigestWorker
import me.parham1995.notes.widget.RecentNotesWidget
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That a launch intent is acted on once and not on every recreation after it.
 *
 * An activity is handed the same intent every time it is created again, so a
 * shortcut to the task list kept sending the person back there each time the
 * phone was turned.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchRequestTest {
    private fun widgetRow(id: Long) = Intent(Intent.ACTION_MAIN).putExtra(RecentNotesWidget.EXTRA_NOTE, id)

    @Test
    fun `a first launch from a widget row asks for its note`() {
        assertThat(launchRequest(widgetRow(42), restoring = false)).isEqualTo(LaunchRequest(note = 42))
    }

    @Test
    fun `the digest notification asks for the task list`() {
        val intent = Intent().putExtra(TaskDigestWorker.EXTRA_OPEN_TASKS, true)
        assertThat(launchRequest(intent, restoring = false)).isEqualTo(LaunchRequest(screen = "tasks"))
    }

    @Test
    fun `a shortcut names its screen`() {
        val intent = Intent().putExtra(EXTRA_OPEN, "search")
        assertThat(launchRequest(intent, restoring = false)).isEqualTo(LaunchRequest(screen = "search"))
    }

    @Test
    fun `a recreation asks for nothing, whatever the intent still carries`() {
        assertThat(launchRequest(widgetRow(42), restoring = true)).isNull()
    }

    @Test
    fun `a task reopened from recents asks for nothing`() {
        val intent = widgetRow(42).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertThat(launchRequest(intent, restoring = false)).isNull()
    }

    @Test
    fun `an intent that has been answered asks for nothing again`() {
        val intent = widgetRow(42).putExtra(EXTRA_OPEN, "tasks")
        intent.consumeLaunchRequest()
        assertThat(launchRequest(intent, restoring = false)).isNull()
    }

    @Test
    fun `a plain launch asks for nothing`() {
        assertThat(launchRequest(Intent(Intent.ACTION_MAIN), restoring = false)).isNull()
    }
}
