package me.parham1995.notes.ui.render

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A callout's header, which was plain text that only sometimes folded. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class CalloutRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        markdown: String,
        actions: RenderActions = RenderActions(),
    ) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        compose.setContent { blocks.forEach { MdBlockView(it, actions, emptySet()) } }
    }

    @Test
    fun `a link in the title can be followed`() {
        val followed = mutableListOf<String>()
        render(
            "> [!tip] Read [[Plan]] first\n> body",
            RenderActions(inline = InlineActions(onWikiLink = { target, _ -> followed += target })),
        )

        compose.onNodeWithText("Read Plan first").performFirstLinkClick()
        assertThat(followed).containsExactly("Plan")
    }

    @Test
    fun `a plus callout starts open and can be shut`() {
        render("> [!tip]+ Open by default\n> the body")

        compose.onNodeWithText("the body").assertExists()
        compose.onNodeWithText("Open by default").performClick()
        compose.onNodeWithText("the body").assertDoesNotExist()
    }

    @Test
    fun `a minus callout starts shut and can be opened`() {
        render("> [!tip]- Shut by default\n> the body")

        compose.onNodeWithText("the body").assertDoesNotExist()
        compose.onNodeWithText("Shut by default").performClick()
        compose.onNodeWithText("the body").assertExists()
    }

    @Test
    fun `an unknown type is called by its own name`() {
        render("> [!recipe]\n> flour")

        compose.onNodeWithText("Recipe").assertExists()
        compose.onNodeWithText("Note").assertDoesNotExist()
    }
}
