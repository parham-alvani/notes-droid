package me.parham1995.notes.widget

import android.content.Context
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.R
import me.parham1995.notes.data.PinnedNote
import me.parham1995.notes.data.ThemeChoice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the pinned notes widget actually puts on the home screen.
 *
 * Composed and asked, because every way this can go wrong compiles: the empty
 * message drawn over a list, a grid where a list was wanted, a card that
 * opens nothing or the wrong note.
 */
@RunWith(RobolectricTestRunner::class)
class PinnedNotesWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val notes =
        listOf(
            PinnedNote(11, 1, "Lists/Shopping.md", "Shopping", "☐ bread\n☑ milk"),
            PinnedNote(12, 1, "یادداشت.md", "یادداشت", "سلام دنیا"),
        )

    private fun GlanceAppWidgetUnitTest.draw(
        pinned: List<PinnedNote>,
        width: Dp = 250.dp,
    ) {
        setContext(context)
        setAppWidgetSize(DpSize(width, 300.dp))
        provideComposable {
            GlanceTheme(widgetColors(ThemeChoice.DARK)) { PinnedNotesContent(pinned) }
        }
    }

    @Test
    fun `nothing pinned says how to pin something`() =
        runGlanceAppWidgetUnitTest {
            draw(emptyList())

            onNode(hasText(context.getString(R.string.widget_pinned_empty))).assertExists()
            onNode(hasTestTag(PINNED_LIST)).assertDoesNotExist()
            onNode(hasTestTag(PINNED_GRID)).assertDoesNotExist()
        }

    @Test
    fun `a narrow widget lists its pins`() =
        runGlanceAppWidgetUnitTest {
            draw(notes, width = 250.dp)

            onNode(hasTestTag(PINNED_LIST)).assertExists()
            onNode(hasTestTag(PINNED_GRID)).assertDoesNotExist()
            onNode(hasText(context.getString(R.string.widget_pinned_empty))).assertDoesNotExist()
        }

    @Test
    fun `a wide widget lays them out in a grid`() =
        runGlanceAppWidgetUnitTest {
            draw(notes, width = 380.dp)

            onNode(hasTestTag(PINNED_GRID)).assertExists()
            onNode(hasTestTag(PINNED_LIST)).assertDoesNotExist()
        }

    @Test
    fun `each card shows its note's first lines and opens that note`() =
        runGlanceAppWidgetUnitTest {
            draw(notes)

            onNode(hasText("☐ bread")).assertExists()
            onNode(hasText("☑ milk")).assertExists()
            // The card is the tap target, and it names its own note -- not
            // whichever note was drawn last.
            onNode(hasStartActivityClickAction(WidgetIntents.openNote(context, 11)!!))
                .assert(hasAnyDescendant(hasText("Shopping")))
            onNode(hasStartActivityClickAction(WidgetIntents.openNote(context, 12)!!))
                .assert(hasAnyDescendant(hasText("سلام دنیا")))
        }

    @Test
    fun `the headline opens the app as it was left`() =
        runGlanceAppWidgetUnitTest {
            draw(notes)

            onNode(hasText(context.getString(R.string.widget_pinned_title)))
                .assertHasStartActivityClickAction(WidgetIntents.openApp(context)!!)
        }

    @Test
    fun `columns follow the width`() {
        assertThat(columnsForWidth(0)).isEqualTo(1)
        assertThat(columnsForWidth(250)).isEqualTo(1)
        assertThat(columnsForWidth(380)).isEqualTo(2)
    }
}
