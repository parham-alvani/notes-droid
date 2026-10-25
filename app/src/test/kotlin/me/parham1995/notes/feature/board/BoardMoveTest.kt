package me.parham1995.notes.feature.board

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.TaskBoard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holding a card and choosing a column, through the real board.
 *
 * What has shipped broken here before is a gesture that is drawn and reaches
 * nothing -- or reaches the wrong row, which for a write is worse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BoardMoveTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        by: TaskBoard.Grouping,
        canWrite: Boolean = true,
        onToggle: (Int) -> Unit = {},
        onMove: (Int, TaskBoard.Drop) -> Unit,
    ) {
        val columns = TaskBoard.columns(BoardFixture.rows, by, BoardFixture.today) { it.facts() }
        compose.setContent {
            BoardColumns(
                columns = columns,
                canWrite = canWrite,
                today = BoardFixture.today,
                onMove = { row, drop -> onMove(row.line, drop) },
                onToggle = { onToggle(it.line) },
                onOpen = {},
            )
        }
    }

    @Test
    fun `holding a card and choosing tomorrow hands back that card and tomorrow's day`() {
        val moved = mutableListOf<Pair<Int, TaskBoard.Drop>>()
        show(TaskBoard.Grouping.DAY) { line, drop -> moved += line to drop }

        // Overdue, the column on screen.
        compose.onNodeWithText("Water the seedlings").performTouchInput { longClick() }
        // Every column that takes a drop, and never Overdue or Later.
        compose.onNodeWithText("Move to Today").assertExists()
        compose.onNodeWithText("Move to Next 7 Days").assertExists()
        compose.onNodeWithText("Move to No Date").assertExists()
        compose.onNodeWithText("Move to Overdue").assertDoesNotExist()
        compose.onNodeWithText("Move to Later").assertDoesNotExist()
        compose.onNodeWithText("Move to Tomorrow").performClick()

        assertThat(moved).containsExactly(3 to TaskBoard.Drop.Day("2026-10-26"))
        compose.onNodeWithText("Move to Tomorrow").assertDoesNotExist()
    }

    @Test
    fun `a status move hands back the status of the column chosen`() {
        val moved = mutableListOf<Pair<Int, TaskBoard.Drop>>()
        show(TaskBoard.Grouping.STATUS) { line, drop -> moved += line to drop }

        compose.onNodeWithText("Sharpen the shears").performTouchInput { longClick() }
        // Not the column it is already in.
        compose.onNodeWithText("Move to To Do").assertDoesNotExist()
        compose.onNodeWithText("Move to Cancelled").assertExists()
        compose.onNodeWithText("Move to In Progress").performClick()

        assertThat(moved).containsExactly(6 to TaskBoard.Drop.Status('/'))
    }

    @Test
    fun `the checkbox ticks the card it is on`() {
        val ticked = mutableListOf<Int>()
        show(TaskBoard.Grouping.STATUS, onToggle = { ticked += it }) { _, _ -> }

        compose
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .onFirst()
            .performClick()

        // The first card of To Do: overdue and high, so first.
        assertThat(ticked).containsExactly(3)
    }

    @Test
    fun `a vault that cannot be written to offers no move and no checkbox`() {
        val moved = mutableListOf<Pair<Int, TaskBoard.Drop>>()
        show(TaskBoard.Grouping.DAY, canWrite = false) { line, drop -> moved += line to drop }

        compose.onNodeWithText("Water the seedlings").performTouchInput { longClick() }

        compose.onAllNodesWithText("Move to", substring = true).assertCountEquals(0)
        compose
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertCountEquals(0)
        assertThat(moved).isEmpty()
    }
}
