package me.parham1995.notes.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.R
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.PinnedNote
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What one note's widget puts on the home screen, and where a tap on it goes.
 * Applied to real views, as the pinned widget's test does. Synthetic.
 */
@RunWith(RobolectricTestRunner::class)
class NoteWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pin = Pin(2L, "Garden/Door Codes.md")
    private val note = PinnedNote(7, 2L, pin.path, "Door Codes", "Gate: 1234\nShed: 4321")

    private fun draw(
        pin: Pin?,
        note: PinnedNote?,
        vault: String? = "garden",
        scrolls: Boolean = false,
    ): View =
        NoteWidget()
            .build(context, WIDGET, pin, note, vault, scrolls)
            .apply(context, FrameLayout(context))

    private fun started() = shadowOf(context as Application).nextStartedActivity

    @Test
    fun `a bound widget shows the whole note and opens it in its own vault`() {
        val root = draw(pin, note)

        assertThat(root.findViewById<TextView>(R.id.widget_headline).text.toString()).isEqualTo("Door Codes")
        val text = root.findViewById<TextView>(R.id.note_text)
        assertThat(text.visibility).isEqualTo(View.VISIBLE)
        assertThat(text.text.toString()).isEqualTo("Gate: 1234\nShed: 4321")

        text.performClick()
        // By link, so the app switches to the note's vault rather than opening
        // it inside whichever vault happens to be active.
        assertThat(started().dataString).isEqualTo("obsidian://open?vault=garden&file=Garden%2FDoor%20Codes.md")
    }

    @Test
    fun `a scrolling widget hands its lines to the list, not the text view`() {
        val root = draw(pin, note, scrolls = true)

        assertThat(root.findViewById<View>(R.id.note_lines).visibility).isEqualTo(View.VISIBLE)
        assertThat(root.findViewById<View>(R.id.note_text).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `an empty widget asks for a note, and a tap chooses one for this widget`() {
        val root = draw(pin = null, note = null)

        val empty = root.findViewById<TextView>(R.id.note_empty)
        assertThat(empty.visibility).isEqualTo(View.VISIBLE)
        assertThat(empty.text.toString()).isEqualTo(context.getString(R.string.widget_note_choose))

        empty.performClick()
        val chooser = started()
        assertThat(chooser.component?.className).isEqualTo(NoteWidgetConfigureActivity::class.java.name)
        assertThat(chooser.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)).isEqualTo(WIDGET)
    }

    @Test
    fun `a note that has gone says so rather than drawing a blank card`() {
        val root = draw(pin, note = null)

        assertThat(root.findViewById<TextView>(R.id.note_empty).text.toString())
            .isEqualTo(context.getString(R.string.widget_note_gone))
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
