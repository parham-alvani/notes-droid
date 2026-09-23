package me.parham1995.notes.ui.render

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That a quote's side rule is drawn at all.
 *
 * It was a box with a width and no height, in a row that never measured its
 * children's height for it -- so it came out zero tall, and every blockquote in
 * the vault was italic text with nothing beside it. It compiled, it rendered,
 * and it was invisible, which is a question about size and takes a test that
 * asks about size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class QuoteLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the side rule runs the height of the quote`() {
        val blocks = MarkdownParser.parseNote("> first line of the quote\n>\n> a second paragraph under it").blocks
        compose.setContent { blocks.forEach { MdBlockView(it, RenderActions(), emptySet()) } }

        val bar = compose.onNodeWithTag(QUOTE_BAR_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("first line of the quote").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("a second paragraph under it").fetchSemanticsNode().boundsInRoot

        assertThat(bar.height).isGreaterThan(0f)
        // From the top of the first line to the bottom of the last.
        assertThat(bar.top).isAtMost(first.top)
        assertThat(bar.bottom).isAtLeast(second.bottom)
    }
}
