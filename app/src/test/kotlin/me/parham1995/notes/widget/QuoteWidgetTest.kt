package me.parham1995.notes.widget

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.MainActivity
import me.parham1995.notes.R
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.QuoteNote
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.markdown.Quote
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a quote widget puts on the home screen, and where a tap on it goes. Synthetic. */
@RunWith(RobolectricTestRunner::class)
class QuoteWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pin = Pin(3L, "Shelf/Sayings.md")
    private val quote = Quote(listOf("A short walk is still a walk."), "A. Walker")
    private val note = QuoteNote(3L, pin.path, "Sayings", listOf(quote))

    private fun GlanceAppWidgetUnitTest.draw(
        pin: Pin?,
        note: QuoteNote?,
        quote: Quote?,
        vault: String? = "shelf",
    ) {
        setContext(context)
        provideComposable {
            GlanceTheme(widgetColors(ThemeChoice.DARK)) {
                QuoteContent(QuoteModel(WIDGET, pin, note, quote, vault))
            }
        }
    }

    @Test
    fun `the quote and its source are shown, and a tap on them moves to the next`() =
        runGlanceAppWidgetUnitTest {
            draw(pin, note, quote)

            onNode(hasText("A short walk is still a walk.")).assertHasRunCallbackClickAction<NextQuote>()
            onNode(hasText("— A. Walker")).assertHasRunCallbackClickAction<NextQuote>()
        }

    @Test
    fun `the title opens the note in its own vault`() =
        runGlanceAppWidgetUnitTest {
            draw(pin, note, quote)

            val link =
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData("obsidian://open?vault=shelf&file=Shelf%2FSayings.md".toUri())
            onNode(hasText("Sayings")).assertHasStartActivityClickAction(link)
        }

    @Test
    fun `an empty widget asks for a note of quotes`() =
        runGlanceAppWidgetUnitTest {
            draw(pin = null, note = null, quote = null)

            onNode(hasText(context.getString(R.string.widget_quote_choose))).assertExists()
            val chooser =
                Intent(context, NoteWidgetConfigureActivity::class.java)
                    .setData("daftar-widget://$WIDGET".toUri())
            onNode(hasStartActivityClickAction(chooser)).assertExists()
        }

    @Test
    fun `a note with no quotes says so`() =
        runGlanceAppWidgetUnitTest {
            draw(pin, note.copy(quotes = emptyList()), quote = null)

            onNode(hasText(context.getString(R.string.widget_quote_none))).assertExists()
        }

    @Test
    fun `a note that has gone says so`() =
        runGlanceAppWidgetUnitTest {
            draw(pin, note = null, quote = null)

            onNode(hasText(context.getString(R.string.widget_note_gone))).assertExists()
        }

    @Test
    fun `taps count for today only, and a new note starts again`() {
        val bindings = QuoteWidgetBindings(context)
        bindings[WIDGET] = pin
        bindings.skip(WIDGET, DAY)
        bindings.skip(WIDGET, DAY)

        assertThat(bindings.skipped(WIDGET, DAY)).isEqualTo(2)
        assertThat(bindings.skipped(WIDGET, DAY + 1)).isEqualTo(0)
        bindings[WIDGET] = Pin(3L, "Other.md")
        assertThat(bindings.skipped(WIDGET, DAY)).isEqualTo(0)
    }

    @Test
    fun `quote widgets keep their notes apart from note widgets`() {
        QuoteWidgetBindings(context)[WIDGET] = pin

        assertThat(QuoteWidgetBindings(context)[WIDGET]).isEqualTo(pin)
        assertThat(NoteWidgetBindings(context)[WIDGET]).isNull()
        QuoteWidgetBindings(context).forget(intArrayOf(WIDGET))
        assertThat(QuoteWidgetBindings(context)[WIDGET]).isNull()
    }

    private companion object {
        const val WIDGET = 43
        const val DAY = 20_382L
    }
}
