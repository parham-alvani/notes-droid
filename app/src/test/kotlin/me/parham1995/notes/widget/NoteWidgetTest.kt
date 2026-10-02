package me.parham1995.notes.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.MainActivity
import me.parham1995.notes.R
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.PinnedNote
import me.parham1995.notes.data.ThemeChoice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What one note's widget puts on the home screen, and where a tap on it goes.
 *
 * Composed the way Glance composes it, then asked -- the tree, not the
 * RemoteViews, which a launcher is needed to apply. Synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class NoteWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pin = Pin(2L, "Garden/Door Codes.md")
    private val note = PinnedNote(7, 2L, pin.path, "Door Codes", "Gate: 1234\nShed: 4321")

    private fun GlanceAppWidgetUnitTest.draw(
        pin: Pin?,
        note: PinnedNote?,
        vault: String? = "garden",
    ) {
        setContext(context)
        provideComposable {
            GlanceTheme(widgetColors(ThemeChoice.DARK)) {
                NoteContent(NoteModel(WIDGET, pin, note, vault))
            }
        }
    }

    @Test
    fun `a bound widget shows the whole note and opens it in its own vault`() =
        runGlanceAppWidgetUnitTest {
            draw(pin, note)

            onNode(hasText("Door Codes")).assertExists()
            onNode(hasText("Gate: 1234")).assertExists()
            // By link, so the app switches to the note's vault rather than
            // opening it inside whichever vault happens to be active.
            val link =
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData("obsidian://open?vault=garden&file=Garden%2FDoor%20Codes.md".toUri())
            onNode(hasText("Shed: 4321")).assertHasStartActivityClickAction(link)
            // The card itself and each of the two lines.
            onAllNodes(hasStartActivityClickAction(link)).assertCountEquals(3)
        }

    @Test
    fun `an empty widget asks for a note, and a tap chooses one for this widget`() =
        runGlanceAppWidgetUnitTest {
            draw(pin = null, note = null)

            onNode(hasText(context.getString(R.string.widget_note_choose))).assertExists()
            val chooser =
                Intent(context, NoteWidgetConfigureActivity::class.java)
                    .setData("daftar-widget://$WIDGET".toUri())
            onNode(hasStartActivityClickAction(chooser)).assertExists()
        }

    @Test
    fun `the chooser is told which widget asked`() {
        val chooser = WidgetIntents.choose(context, WIDGET)

        assertThat(chooser.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)).isEqualTo(WIDGET)
        assertThat(chooser.component?.className).isEqualTo(NoteWidgetConfigureActivity::class.java.name)
    }

    @Test
    fun `a note that has gone says so rather than drawing a blank card`() =
        runGlanceAppWidgetUnitTest {
            draw(pin, note = null)

            onNode(hasText(context.getString(R.string.widget_note_gone))).assertExists()
            onNode(hasText(context.getString(R.string.widget_note_choose))).assertDoesNotExist()
        }

    @Test
    fun `each widget keeps its own note, and forgets it when removed`() {
        val bindings = NoteWidgetBindings(context)
        bindings[1] = Pin(1L, "A.md")
        bindings[2] = Pin(2L, "Folder/B: with a colon.md")

        assertThat(NoteWidgetBindings(context)[2]).isEqualTo(Pin(2L, "Folder/B: with a colon.md"))
        bindings.forget(intArrayOf(1))
        assertThat(NoteWidgetBindings(context)[1]).isNull()
        assertThat(NoteWidgetBindings(context)[2]).isNotNull()
    }

    private companion object {
        const val WIDGET = 42
    }
}
