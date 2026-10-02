package me.parham1995.notes.widget

import android.content.Context
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasAnyDescendant
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import me.parham1995.notes.R
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.database.NoteEntity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The recent notes widget: each row opens its own note, which is the entire
 * point of it, and a note with no title goes by its name.
 */
@RunWith(RobolectricTestRunner::class)
class RecentNotesWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun note(
        id: Long,
        name: String,
        title: String = name,
    ) = NoteEntity(
        id = id,
        vaultId = 1,
        path = "$name.md",
        parent = "",
        name = name,
        slug = name.lowercase(),
        title = title,
        blobSha = "s",
        size = 1,
        isFolderNote = false,
        isRtl = false,
        hasMermaid = false,
        hasMath = false,
        indexedAt = 0,
    )

    private fun GlanceAppWidgetUnitTest.draw(
        recent: List<NoteEntity>,
        height: Dp = 110.dp,
    ) {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, height))
        provideComposable {
            GlanceTheme(widgetColors(ThemeChoice.DARK)) { RecentNotesContent(recent) }
        }
    }

    @Test
    fun `each row opens its own note`() =
        runGlanceAppWidgetUnitTest {
            draw(listOf(note(1, "Alpha"), note(2, "Beta", title = "")))

            onNode(hasStartActivityClickAction(WidgetIntents.openNote(context, 1)!!))
                .assert(hasAnyDescendant(hasText("Alpha")))
            // No title: the file's name stands in.
            onNode(hasStartActivityClickAction(WidgetIntents.openNote(context, 2)!!))
                .assert(hasAnyDescendant(hasText("Beta")))
        }

    @Test
    fun `nothing read yet says so, and still opens the app`() =
        runGlanceAppWidgetUnitTest {
            draw(emptyList())

            onNode(hasText(context.getString(R.string.widget_recent_empty))).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.openApp(context)!!)).assertExists()
        }

    @Test
    fun `the rows follow the height`() =
        runGlanceAppWidgetUnitTest {
            draw((1..MAX_ROWS).map { note(it.toLong(), "Note $it") }, height = 110.dp)

            onNode(hasText(context.getString(R.string.widget_recent_title))).assertExists()
            onAllNodes(hasText("Note ")).assertCountEquals(rowsForHeight(110))
        }

    @Test
    fun `a tall widget is filled`() =
        runGlanceAppWidgetUnitTest {
            draw((1..MAX_ROWS).map { note(it.toLong(), "Note $it") }, height = 344.dp)

            val rows = rowsForHeight(344)
            onAllNodes(hasText("Note ")).assertCountEquals(rows)
            // The last row drawn is wired, and the first row not drawn is not
            // there at all -- neither a row that opens nothing nor one too many.
            onNode(hasStartActivityClickAction(WidgetIntents.openNote(context, rows.toLong())!!)).assertExists()
            onNode(hasStartActivityClickAction(WidgetIntents.openNote(context, rows + 1L)!!)).assertDoesNotExist()
        }
}
