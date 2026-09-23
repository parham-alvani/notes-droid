package me.parham1995.notes.ui.render

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which way a tapped Markdown link goes.
 *
 * Every `[text](destination)` was handed to the system as a web address, so a
 * link to another note opened nothing and said nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class LinkRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private val internal = mutableListOf<String>()
    private val external = mutableListOf<String>()

    private fun render(markdown: String) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        val actions =
            RenderActions(
                inline =
                    InlineActions(
                        onInternalLink = { internal += it },
                        onExternalLink = { external += it },
                    ),
            )
        compose.setContent { blocks.forEach { MdBlockView(it, actions, emptySet()) } }
    }

    @Test
    fun `a link to another note stays inside the vault`() {
        render("See [the plan](Other%20Note.md#Some%20Part).")

        compose.onNodeWithText("See the plan.").performFirstLinkClick()
        assertThat(internal).containsExactly("Other%20Note.md#Some%20Part")
        assertThat(external).isEmpty()
    }

    @Test
    fun `a web address still goes to the browser`() {
        render("See [the site](https://example.com).")

        compose.onNodeWithText("See the site.").performFirstLinkClick()
        assertThat(external).containsExactly("https://example.com")
        assertThat(internal).isEmpty()
    }
}
