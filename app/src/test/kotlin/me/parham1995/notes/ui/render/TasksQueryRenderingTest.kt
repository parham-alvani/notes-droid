package me.parham1995.notes.ui.render

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import me.parham1995.notes.data.database.QueryTaskRow
import me.parham1995.notes.markdown.MarkdownParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A ` ```tasks ` fence, drawn: that the query reaches the loader, that its
 * answer is listed, and that a box and a row each do what they say.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TasksQueryRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(
        id: Long,
        text: String,
        state: String = "UNCHECKED",
        noteId: Long = 7,
        section: String = "Plan",
    ) = QueryTaskRow(
        id = id,
        noteId = noteId,
        text = text,
        state = state,
        section = section,
        blockIndex = 0,
        line = id.toInt(),
        actionableOn = null,
        recurring = null,
        scheduled = "2026-10-09",
        due = null,
        done = null,
        notePath = "Projects/Alpha.md",
        noteTitle = "Alpha",
        vaultId = 1,
    )

    private fun render(
        actions: RenderActions,
        markdown: String = "```tasks\nnot done\n```",
    ) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        compose.setContent { blocks.forEach { MdBlockView(it, actions, emptySet()) } }
    }

    @Test
    fun `the fence's body is the query asked, and its answer is listed`() {
        val asked = mutableListOf<String>()
        render(
            RenderActions(
                queryTasks = { query ->
                    asked += query
                    flowOf(TasksQueryResult(listOf(row(1, "write the report"), row(2, "call the bank")), emptyList()))
                },
            ),
            markdown = "```tasks\nnot done\ndue today\n```",
        )

        assertThat(asked).containsExactly("not done\ndue today")
        compose.onNodeWithText("write the report").assertExists()
        compose.onNodeWithText("call the bank").assertExists()
        compose.onAllNodesWithText("Alpha · 2026-10-09").assertCountEquals(2)
    }

    @Test
    fun `the box ticks that task and the row opens its note at its heading`() {
        val ticked = mutableListOf<QueryTaskRow>()
        val opened = mutableListOf<Pair<Long, String?>>()
        render(
            RenderActions(
                queryTasks = {
                    flowOf(
                        TasksQueryResult(
                            listOf(row(3, "water the plants", noteId = 42, section = "Garden")),
                            emptyList(),
                        ),
                    )
                },
                onCompleteQueryTask = { ticked += it },
                inline = InlineActions(onNoteLink = { id, heading -> opened += id to heading }),
            ),
        )

        compose.onNodeWithContentDescription("unchecked").performClick()
        assertThat(ticked.map { it.id }).containsExactly(3L)
        assertThat(opened).isEmpty()

        compose.onNodeWithText("water the plants").performClick()
        assertThat(opened).containsExactly(42L to "Garden")
    }

    @Test
    fun `a finished task's box does nothing`() {
        val ticked = mutableListOf<QueryTaskRow>()
        render(
            RenderActions(
                queryTasks = { flowOf(TasksQueryResult(listOf(row(4, "shipped", state = "CHECKED")), emptyList())) },
                onCompleteQueryTask = { ticked += it },
            ),
        )

        compose.onNodeWithContentDescription("checked").performClick()
        assertThat(ticked).isEmpty()
    }

    @Test
    fun `groups are headed once where they start, and an empty one has no heading`() {
        render(
            RenderActions(
                queryTasks = {
                    flowOf(
                        TasksQueryResult(
                            rows =
                                listOf(
                                    row(1, "mend the fence"),
                                    row(2, "paint the shed"),
                                    row(3, "prune the roses"),
                                ),
                            notUnderstood = emptyList(),
                            groups =
                                listOf(
                                    listOf("Garden", ""),
                                    listOf("Garden", "Autumn"),
                                    listOf("Garden", "Spring"),
                                ),
                        ),
                    )
                },
            ),
        )

        compose.onAllNodesWithText("Garden").assertCountEquals(1)
        compose.onNodeWithText("Autumn").assertExists()
        compose.onNodeWithText("Spring").assertExists()
    }

    @Test
    fun `a line it could not read is said, and nothing matching is said too`() {
        render(
            RenderActions(queryTasks = { flowOf(TasksQueryResult(emptyList(), listOf("priority is high"))) }),
        )

        compose.onNodeWithText("No tasks match this query.").assertExists()
        compose.onNodeWithText("Not understood, so not applied: priority is high").assertExists()
    }

    @Test
    fun `with nothing to answer it, the query says so rather than showing its source`() {
        render(RenderActions(queryTasks = null))

        compose.onNodeWithText("Tasks query - not supported here").assertExists()
        compose.onNodeWithText("not done").assertDoesNotExist()
    }
}
