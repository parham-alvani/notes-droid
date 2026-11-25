package me.parham1995.notes.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.TaskFields
import me.parham1995.notes.markdown.TaskLine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The New task form holds what it shows: the section it opened on and the
 * created day, today unless taken off; Done on the keyboard adds the task.
 * The form rather than the dialog, as `NewNoteDialogTest` does: a dialog is
 * a window of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AddTaskDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val form = AddTaskState("Tasks/Work.md", "Beta")
    private var done = 0

    private fun show() {
        compose.setContent {
            AddTaskForm(
                form = form,
                files = listOf("Work" to "Tasks/Work.md"),
                sectionsOf = { listOf("Alpha", "Beta") },
                onDone = { done++ },
            )
        }
    }

    @Test
    fun `a task is added where the form opened, created today`() {
        show()
        compose.onNodeWithText("Beta").assertExists()
        compose.onNodeWithText("What needs doing").performTextInput("Buy milk")
        compose.onNodeWithText("Buy milk").performImeAction()

        assertThat(done).isEqualTo(1)
        assertThat(listOf(form.path, form.section, form.text, form.created))
            .containsExactly("Tasks/Work.md", "Beta", "Buy milk", LocalDate.now())
            .inOrder()
    }

    @Test
    fun `the created day can be taken off`() {
        show()
        compose.onNodeWithText(LocalDate.now().toString()).assertExists()
        // The first Clear is the created day's; the scheduled day has its own.
        compose.onAllNodesWithText("Clear")[0].performClick()

        compose.onNodeWithText("No date").assertExists()
        assertThat(form.created).isNull()
        assertThat(form.scheduled).isNotNull()
    }

    /** `aNewTaskIsScheduled` of `more-vault-settings.md`: the form starts with ⏳ the vault's days after today. */
    @Test
    fun aNewTaskIsScheduled() {
        val today = LocalDate.of(2026, 11, 25)
        val scheduled = AddTaskState("Tasks/Work.md", today = today, scheduledDays = 3)
        assertThat(scheduled.created).isEqualTo(today)
        assertThat(scheduled.scheduled).isEqualTo(LocalDate.of(2026, 11, 28))
        assertThat(line(scheduled)).isEqualTo("- [ ] Call ➕ 2026-11-25 ⏳ 2026-11-28")

        val none = AddTaskState("Tasks/Work.md", today = today, scheduledDays = 0)
        assertThat(none.scheduled).isNull()
        assertThat(line(none)).isEqualTo("- [ ] Call ➕ 2026-11-25")
    }

    @Test
    fun `the scheduled day can be taken off`() {
        show()
        compose.onNodeWithText(LocalDate.now().plusDays(3).toString()).assertExists()
        compose.onAllNodesWithText("Clear")[1].performClick()
        assertThat(form.scheduled).isNull()
    }

    private fun line(form: AddTaskState): String? =
        TaskLine.written(
            TaskFields(text = "Call", created = form.created?.toString(), scheduled = form.scheduled?.toString()),
        )
}
