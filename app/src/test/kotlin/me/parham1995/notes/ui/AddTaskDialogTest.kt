package me.parham1995.notes.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
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
        compose.onNodeWithText("Clear").performClick()

        compose.onNodeWithText("No date").assertExists()
        assertThat(form.created).isNull()
    }
}
