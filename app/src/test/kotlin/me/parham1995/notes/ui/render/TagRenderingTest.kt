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

/**
 * Tags and front matter, where a tap has to reach somewhere.
 *
 * The failure worth guarding is the one this app has shipped before: drawn,
 * styled, and wired to nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TagRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private val tapped = mutableListOf<String>()

    private fun render(markdown: String) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        val actions = RenderActions(inline = InlineActions(onTag = { tapped += it }))
        compose.setContent { blocks.forEach { MdBlockView(it, actions, emptySet()) } }
    }

    @Test
    fun `a tag in the text hands back its name`() {
        render("Filed under #project/alpha today.")

        compose.onNodeWithText("Filed under #project/alpha today.").performFirstLinkClick()
        assertThat(tapped).containsExactly("project/alpha")
    }

    @Test
    fun `properties are shown, and a tag among them opens its list`() {
        render("---\nstatus: draft\nsources: [one, two]\ntags: [idea]\n---\nBody")

        compose.onNodeWithText("status").assertExists()
        compose.onNodeWithText("draft").assertExists()
        compose.onNodeWithText("one").assertExists()
        compose.onNodeWithText("two").assertExists()
        compose.onNodeWithText("#idea").performClick()
        assertThat(tapped).containsExactly("idea")
    }

    @Test
    fun `the properties block folds shut`() {
        render("---\nstatus: draft\n---\nBody")

        compose.onNodeWithText("Properties").performClick()
        compose.onNodeWithText("draft").assertDoesNotExist()
        compose.onNodeWithText("Properties").performClick()
        compose.onNodeWithText("draft").assertExists()
    }
}
