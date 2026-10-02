package me.parham1995.notes.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.navigation.LaunchRequest
import me.parham1995.notes.navigation.launchRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That what a widget sends is what the app reads.
 *
 * The intents carry their target twice -- in an extra the app reads and in a
 * data URI the system tells them apart by -- and nothing but this test checks
 * the two halves agree.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetIntentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `a row's intent asks for its note`() {
        assertThat(launchRequest(WidgetIntents.openNote(context, 42), restoring = false))
            .isEqualTo(LaunchRequest(note = 42))
    }

    @Test
    fun `the tasks widget asks for the task list`() {
        assertThat(launchRequest(WidgetIntents.openTasks(context), restoring = false))
            .isEqualTo(LaunchRequest(screen = "tasks"))
    }

    @Test
    fun `the note widget opens by link, so the vault switches first`() {
        val link = WidgetIntents.openLink(context, "garden", "Garden/Door Codes.md")

        assertThat(launchRequest(link, restoring = false))
            .isEqualTo(LaunchRequest(link = "obsidian://open?vault=garden&file=Garden%2FDoor%20Codes.md"))
    }

    @Test
    fun `the app as it was left asks for nothing in particular`() {
        assertThat(launchRequest(WidgetIntents.openApp(context), restoring = false)).isNull()
    }

    @Test
    fun `two notes are two intents to the system, not one`() {
        val first = WidgetIntents.openNote(context, 1)!!
        val second = WidgetIntents.openNote(context, 2)!!

        assertThat(first.filterEquals(second)).isFalse()
    }
}
