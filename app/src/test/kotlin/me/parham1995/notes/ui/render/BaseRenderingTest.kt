package me.parham1995.notes.ui.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.BaseAnswer
import me.parham1995.notes.markdown.BaseCell
import me.parham1995.notes.markdown.BaseImage
import me.parham1995.notes.markdown.BaseLayout
import me.parham1995.notes.markdown.BaseResult
import me.parham1995.notes.markdown.BaseTable
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.MdBlock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A base, drawn: the block reaches the runner, every view is a tab, a filter
 * that was not applied is said, and a name opens its note.
 *
 * Every fixture here is made up. The repository is public; the vault is not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BaseRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun table(
        name: String,
        vararg rows: Pair<String, String>,
    ) = BaseTable(
        name = name,
        type = "table",
        columns = listOf("file name", "stage"),
        groups =
            listOf(
                BaseTable.Group(
                    null,
                    rows.map { (path, stage) ->
                        BaseTable.Row(
                            path,
                            listOf(
                                BaseCell(listOf(BaseCell.Part(path.substringAfterLast('/').removeSuffix(".md"), path))),
                                BaseCell(listOf(BaseCell.Part(stage))),
                            ),
                        )
                    },
                ),
            ),
        count = rows.size,
    )

    private val answer =
        BaseAnswer(
            BaseResult(
                tables =
                    listOf(
                        table("Open", "Shops/Bakery.md" to "open"),
                        table("Closed", "Shops/Cafe.md" to "closed"),
                    ),
                problems = listOf("Filter not understood, so not applied: stage =="),
            ),
            path = "Shops/Shops.base",
            noteIds = mapOf("Shops/Bakery.md" to 11L, "Shops/Cafe.md" to 12L),
        )

    @Test
    fun `an embed reaches the runner, and each view is a tab`() {
        val asked = mutableListOf<MdBlock.BaseView>()
        val opened = mutableListOf<Long>()
        val blocks = MarkdownParser.parseNote("![[Shops.base]]").blocks
        compose.setContent {
            blocks.forEach {
                MdBlockView(
                    it,
                    RenderActions(
                        runBase = { block ->
                            asked += block
                            answer
                        },
                        inline = InlineActions(onNoteLink = { id, _ -> opened += id }),
                    ),
                    emptySet(),
                )
            }
        }

        compose.onNodeWithText("Filter not understood, so not applied: stage ==").assertExists()
        compose.onNodeWithText("Bakery").assertExists()
        compose.onNodeWithText("Cafe").assertDoesNotExist()

        compose.onNodeWithText("Closed").performClick()
        compose.onNodeWithText("Cafe").assertExists().performClick()

        assertThat(asked.single().target).isEqualTo("Shops.base")
        assertThat(opened).containsExactly(12L)
    }

    @Test
    fun `an embed that names a view shows that view alone`() {
        compose.setContent {
            BaseTables(title = "Shops", answer = Result.success(answer), onlyView = "closed", onOpen = {})
        }
        compose.onNodeWithText("Cafe").assertExists()
        compose.onNodeWithText("Bakery").assertDoesNotExist()
        compose.onNodeWithText("Open").assertDoesNotExist()
    }

    @Test
    fun `a cards view is a card per note, titled by its name, with its other values`() {
        val cards =
            table("Gallery", "Shops/Bakery.md" to "open", "Shops/Cafe.md" to "closed").let { view ->
                view.copy(
                    type = "cards",
                    kind = "cards",
                    // Two across on the phone, so both are on screen.
                    layout = BaseLayout(cardSize = 150.0),
                    groups =
                        view.groups.map { group ->
                            group.copy(rows = group.rows.map { it.copy(image = BaseImage.Color("#ff5070")) })
                        },
                )
            }
        val opened = mutableListOf<String>()
        compose.setContent {
            BaseTables(
                title = "Shops",
                answer =
                    Result.success(
                        answer.copy(BaseResult(listOf(cards), emptyList())),
                    ),
                onlyView = null,
                onOpen = {
                    opened +=
                        it
                },
            )
        }
        compose.onAllNodesWithTag("base-card").assertCountEquals(2)
        // The name is the title, so the name column is not a value again.
        compose.onAllNodesWithText("Bakery").assertCountEquals(1)
        compose.onAllNodesWithText("stage").assertCountEquals(2)
        compose.onNodeWithText("closed").assertExists()
        compose.onNodeWithText("Cafe").performClick()
        assertThat(opened).containsExactly("Shops/Cafe.md")
    }

    @Test
    fun `a list view is an item per note, numbered when it asks`() {
        val list =
            table("Lines", "Shops/Bakery.md" to "open", "Shops/Cafe.md" to "").copy(
                type = "list",
                kind = "list",
                layout = BaseLayout(markers = "number", separator = "|"),
            )
        compose.setContent {
            BaseTables(
                title = "Shops",
                answer =
                    Result.success(
                        answer.copy(BaseResult(listOf(list), emptyList())),
                    ),
                onlyView = null,
                onOpen = {
                },
            )
        }
        compose.onAllNodesWithTag("base-item").assertCountEquals(2)
        compose.onNodeWithText("1.").assertExists()
        compose.onNodeWithText("2.").assertExists()
        compose.onNodeWithText("Bakery open").assertExists()
        // An empty value is left out.
        compose.onNodeWithText("Cafe").assertExists()
    }

    @Test
    fun `a cover colour is read as CSS reads it`() {
        assertThat(hexColor("#ff5070")).isEqualTo(Color(0xFFFF5070))
        assertThat(hexColor("#f00")).isEqualTo(Color(0xFFFF0000))
        assertThat(hexColor("#ff000080")).isEqualTo(Color(0x80FF0000))
        assertThat(hexColor("#12345")).isNull()
    }

    @Test
    fun `a base that is not there says so`() {
        compose.setContent {
            BaseTables(title = "Shops", answer = Result.success(null), onlyView = null, onOpen = {})
        }
        compose.onNodeWithText("This base is not in the vault.").assertExists()
    }
}
