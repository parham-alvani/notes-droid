package me.parham1995.notes.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.VaultBookmarks
import me.parham1995.notes.obsidian.Bookmark
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That each kind of bookmark, tapped, asks for the thing it names -- the layer
 * where this app's features have shipped drawn and inert. Fixtures are
 * synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookmarkListTest {
    @get:Rule
    val compose = createComposeRule()

    private val bookmarks =
        VaultBookmarks(
            vaultId = 1L,
            items =
                listOf(
                    Bookmark.File("Projects/Plan.md"),
                    Bookmark.File("Guide.md", heading = "Setup"),
                    Bookmark.File("Gone.md"),
                    Bookmark.Folder("Projects/Archive"),
                    Bookmark.Search("red harvest", title = "Harvest"),
                    Bookmark.Group("Reading", listOf(Bookmark.File("Books/One.md"))),
                ),
            noteIds = mapOf("Projects/Plan.md" to 11L, "Guide.md" to 12L, "Books/One.md" to 13L),
        )

    private val asked = mutableListOf<String>()

    private fun render() {
        compose.setContent {
            var open by remember { mutableStateOf(emptySet<String>()) }
            val actions =
                BookmarkActions(
                    onNote = { id, heading -> asked += "note $id ${heading.orEmpty()}".trim() },
                    onFolder = { asked += "folder $it" },
                    onSearch = { asked += "search $it" },
                    onUrl = { asked += "url $it" },
                    onMissing = { asked += "missing $it" },
                    onToggleGroup = { key -> open = if (key in open) open - key else open + key },
                )
            LazyColumn { bookmarkItems(bookmarkNodes(bookmarks, open), open, actions) }
        }
    }

    @Test
    fun `each kind asks for what it names`() {
        render()

        compose.onNodeWithText("Plan").performClick()
        compose.onNodeWithText("Guide › Setup").performClick()
        compose.onNodeWithText("Gone").performClick()
        compose.onNodeWithText("Archive").performClick()
        compose.onNodeWithText("Harvest").performClick()

        assertThat(asked)
            .containsExactly(
                "note 11",
                "note 12 Setup",
                "missing Gone.md",
                "folder Projects/Archive",
                "search red harvest",
            ).inOrder()
    }

    @Test
    fun `a group opens to show what is in it, and shuts again`() {
        render()
        compose.onNodeWithText("One").assertDoesNotExist()

        compose.onNodeWithText("Reading").performClick()
        compose.onNodeWithText("One").performClick()
        assertThat(asked).containsExactly("note 13")

        compose.onNodeWithText("Reading").performClick()
        compose.onNodeWithText("One").assertDoesNotExist()
    }

    @Test
    fun `nested rows sit a step in`() {
        val nodes = bookmarkNodes(bookmarks, open = setOf("5"))
        assertThat(nodes.map { it.bookmark.label to it.depth }.last()).isEqualTo("One" to 1)
        assertThat(nodes.single { it.bookmark.label == "Gone" }.noteId).isNull()
    }
}
