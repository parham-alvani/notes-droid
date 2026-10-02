package me.parham1995.notes.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import me.parham1995.notes.R
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.feature.capture.CaptureActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * What the tasks widget says, how much of it, and where its taps go.
 *
 * The count is the point of the widget, so the headline is checked for each
 * shape it takes; the rows are checked against the size, because a widget
 * that drew four rows in a box built for twelve is what the size logic
 * exists to prevent.
 */
@RunWith(RobolectricTestRunner::class)
class TasksWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val today = LocalDate.of(2026, 10, 2)

    private fun task(
        id: Long,
        text: String,
        on: LocalDate?,
    ) = TaskRow(
        id = id,
        noteId = 1,
        text = text,
        state = " ",
        section = "",
        blockIndex = 0,
        line = 0,
        actionableOn = on?.toString(),
        recurring = null,
        notePath = "Tasks.md",
        noteTitle = "Tasks",
        vaultId = 1,
    )

    private fun GlanceAppWidgetUnitTest.draw(
        model: TasksModel,
        height: Dp = 110.dp,
    ) {
        setContext(context)
        setAppWidgetSize(DpSize(250.dp, height))
        provideComposable {
            GlanceTheme(widgetColors(ThemeChoice.DARK)) { TasksContent(model) }
        }
    }

    @Test
    fun `the headline counts what is late and what is due, and marks the late ones`() =
        runGlanceAppWidgetUnitTest {
            val due = listOf(task(1, "Pay rent", today.minusDays(3)), task(2, "Call the plumber", today))
            draw(TasksModel(openCount = 9, due = due, today = today))

            onNode(hasText(context.getString(R.string.widget_tasks_both, 1, 1))).assertExists()
            onNode(hasText("Pay rent")).assertExists()
            onNode(hasText("Call the plumber")).assertExists()
            onNode(hasText("!")).assertExists()
        }

    @Test
    fun `open tasks with nothing due are counted, not listed`() =
        runGlanceAppWidgetUnitTest {
            draw(TasksModel(openCount = 4, due = emptyList(), today = today))

            onNode(hasText(context.getString(R.string.widget_tasks_none_due, 4))).assertExists()
            onNode(hasText("!")).assertDoesNotExist()
        }

    @Test
    fun `nothing open says so`() =
        runGlanceAppWidgetUnitTest {
            draw(TasksModel(openCount = 0, due = emptyList(), today = today))

            onNode(hasText(context.getString(R.string.widget_tasks_none))).assertExists()
        }

    @Test
    fun `the rows follow the height`() =
        runGlanceAppWidgetUnitTest {
            val due = (1..10).map { task(it.toLong(), "Chore $it", today) }
            draw(TasksModel(openCount = 10, due = due, today = today), height = 110.dp)

            onAllNodes(hasText("Chore ")).assertCountEquals(rowsForHeight(110))
        }

    @Test
    fun `a tall widget lists every pressing task`() =
        runGlanceAppWidgetUnitTest {
            val due = (1..10).map { task(it.toLong(), "Chore $it", today) }
            draw(TasksModel(openCount = 10, due = due, today = today), height = 344.dp)

            onAllNodes(hasText("Chore ")).assertCountEquals(10)
        }

    @Test
    fun `the widget opens the task list, and its glyph the capture sheet`() =
        runGlanceAppWidgetUnitTest {
            draw(TasksModel(openCount = 1, due = listOf(task(1, "Pay rent", today)), today = today))

            onNode(hasStartActivityClickAction(WidgetIntents.openTasks(context)!!)).assertExists()
            onNode(hasText(context.getString(R.string.widget_capture_glyph)))
                .assertHasStartActivityClickAction(Intent(context, CaptureActivity::class.java))
        }
}
