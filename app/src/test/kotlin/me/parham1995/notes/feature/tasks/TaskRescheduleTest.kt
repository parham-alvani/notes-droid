package me.parham1995.notes.feature.tasks

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.TaskBucket
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.ui.RescheduleChoices
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Holding a task and choosing a day, through the real list.
 *
 * The failure worth guarding against is the one this app has shipped before: a
 * gesture that is drawn, compiles, and reaches nothing -- or reaches the wrong
 * row, which for a write is worse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TaskRescheduleTest {
    @get:Rule
    val compose = createComposeRule()

    /** A Wednesday, so tomorrow, the weekend and next week are three days. */
    private val today = LocalDate.of(2026, 9, 23)

    private fun row(
        id: Long,
        text: String,
        line: Int,
    ) = TaskRow(
        id = id,
        noteId = 1,
        text = text,
        state = "UNCHECKED",
        section = "Home",
        blockIndex = 0,
        line = line,
        actionableOn = "2026-09-01",
        recurring = null,
        notePath = "Tasks/Home.md",
        noteTitle = "Home",
        vaultId = 1,
    )

    private fun show(
        canWrite: Boolean,
        onReschedule: (TaskRow, LocalDate) -> Unit,
    ) {
        val rows = listOf(row(1, "call the bank", line = 4), row(2, "water the plants", line = 9))
        compose.setContent {
            TaskList(
                groups = listOf(TaskGroup(TaskBucket.OVERDUE, rows)),
                canWrite = canWrite,
                today = today,
                onComplete = {},
                onReschedule = onReschedule,
                onOpen = {},
            )
        }
    }

    @Test
    fun `holding a task and choosing tomorrow moves that task to tomorrow`() {
        val moved = mutableListOf<Pair<Int, LocalDate>>()
        show(canWrite = true) { row, date -> moved += row.line to date }

        compose.onNodeWithText("water the plants").performTouchInput { longClick() }
        compose.onNodeWithText("Tomorrow").performClick()

        // The second row, by the line it was written on, and the day after
        // the fixed today -- not whatever day the test happens to run on.
        assertThat(moved).containsExactly(9 to LocalDate.of(2026, 9, 24))
        // And the sheet is gone once a day is chosen.
        compose.onNodeWithText("Tomorrow").assertDoesNotExist()
    }

    @Test
    fun `a vault that cannot be written to offers nothing to hold`() {
        val moved = mutableListOf<Pair<Int, LocalDate>>()
        show(canWrite = false) { row, date -> moved += row.line to date }

        compose.onNodeWithText("water the plants").performTouchInput { longClick() }

        compose.onNodeWithText("Tomorrow").assertDoesNotExist()
        assertThat(moved).isEmpty()
    }

    @Test
    fun `the fixed choices are always after today`() {
        val wednesday = RescheduleChoices.from(today)
        assertThat(wednesday.weekend).isEqualTo(LocalDate.of(2026, 9, 26))
        assertThat(wednesday.nextWeek).isEqualTo(LocalDate.of(2026, 9, 28))

        // On a Saturday the weekend is the next one, and on a Monday next week
        // is a week away: pushing a task to the day it is already on is not
        // pushing it.
        assertThat(RescheduleChoices.from(LocalDate.of(2026, 9, 26)).weekend).isEqualTo(LocalDate.of(2026, 10, 3))
        assertThat(RescheduleChoices.from(LocalDate.of(2026, 9, 28)).nextWeek).isEqualTo(LocalDate.of(2026, 10, 5))
    }
}
