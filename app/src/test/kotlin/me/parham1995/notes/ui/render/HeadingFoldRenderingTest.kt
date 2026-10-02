package me.parham1995.notes.ui.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.parham1995.notes.markdown.HeadingFolds
import me.parham1995.notes.markdown.MarkdownParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Folding a section from its heading, through the real page.
 *
 * The fold state lives outside the document, the way the note screen holds
 * it, so this is the whole loop: a chevron that reaches a handler, a handler
 * whose answer takes the section off the screen, and a chevron that then says
 * so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class HeadingFoldRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private var folded by mutableStateOf(emptySet<Int>())

    private fun render(
        markdown: String,
        foldable: Boolean = true,
    ) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        compose.setContent {
            MarkdownDocument(
                blocks = blocks,
                actions = RenderActions(),
                folded = folded,
                onToggleFold =
                    if (foldable) {
                        { index -> folded = if (index in folded) folded - index else folded + index }
                    } else {
                        null
                    },
            )
        }
    }

    private val note = "## First\n\none\n\n## Second\n\ntwo\n"

    @Test
    fun `tapping a heading's chevron hides its section and nothing else`() {
        render(note)
        compose.onNodeWithText("one").assertExists()

        compose.onAllNodesWithContentDescription("Fold section")[0].performClick()

        compose.onNodeWithText("one").assertDoesNotExist()
        compose.onNodeWithText("First").assertExists()
        compose.onNodeWithText("two").assertExists()
        // The chevron now offers the way back.
        compose.onNodeWithContentDescription("Unfold section").assertExists()
    }

    @Test
    fun `tapping it again brings the section back`() {
        render(note)
        compose.onAllNodesWithContentDescription("Fold section")[0].performClick()
        compose.onNodeWithContentDescription("Unfold section").performClick()

        compose.onNodeWithText("one").assertExists()
        compose.onAllNodesWithContentDescription("Fold section").assertCountEquals(2)
    }

    @Test
    fun `a heading with nothing under it has no chevron`() {
        render("# Alone\n\n## Then\n\ntext\n\n## Last\n")
        // Alone owns Then and Last; Then owns text; Last owns nothing.
        compose.onAllNodesWithContentDescription("Fold section").assertCountEquals(2)
    }

    @Test
    fun `a page that does not fold shows no chevrons at all`() {
        render(note, foldable = false)
        compose.onAllNodesWithContentDescription("Fold section").assertCountEquals(0)
    }

    @Test
    fun `a fold the model says is shut is drawn shut`() {
        folded = setOf(HeadingFolds.visible(MarkdownParser.parseNote(note).blocks, emptySet()).first())
        render(note)
        compose.onNodeWithText("one").assertDoesNotExist()
        compose.onNodeWithText("two").assertExists()
    }
}
