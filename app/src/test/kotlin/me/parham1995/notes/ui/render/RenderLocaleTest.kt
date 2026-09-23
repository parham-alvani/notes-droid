package me.parham1995.notes.ui.render

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import me.parham1995.notes.markdown.MarkdownParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** That what the renderer says about a block it cannot draw is in the reader's language. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fa-w411dp-h891dp")
class RenderLocaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an unsupported block is described in Persian`() {
        val blocks = MarkdownParser.parseNote("```dataview\nLIST FROM #x\n```").blocks
        compose.setContent { blocks.forEach { MdBlockView(it, RenderActions(), emptySet()) } }

        compose.onNodeWithText("not supported here", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Dataview", substring = true).assertExists()
    }
}
