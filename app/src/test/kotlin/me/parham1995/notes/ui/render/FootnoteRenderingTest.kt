package me.parham1995.notes.ui.render

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.footnotes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FootnoteRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private val note = "A claim[^src] here.\n\n[^src]: From [[Source Note]]."

    @Test
    fun `tapping a footnote number hands back its label`() {
        val tapped = mutableListOf<String>()
        val blocks = MarkdownParser.parseNote(note).blocks
        val actions = RenderActions(inline = InlineActions(onFootnote = { tapped += it }))
        compose.setContent { blocks.forEach { MdBlockView(it, actions, emptySet()) } }

        // The number is set in the sentence, where it was cited.
        compose.onNodeWithText("A claim1 here.").performFirstLinkClick()
        assertThat(tapped).containsExactly("src")
    }

    @Test
    fun `the footnotes are listed at the end`() {
        val blocks = MarkdownParser.parseNote(note).blocks
        compose.setContent { blocks.forEach { MdBlockView(it, RenderActions(), emptySet()) } }

        compose.onNodeWithText("Footnotes").assertExists()
        compose.onNodeWithText("1.").assertExists()
        compose.onNodeWithText("From Source Note.").assertExists()
    }

    @Test
    fun `a link in the footnote sheet can be followed`() {
        val followed = mutableListOf<String>()
        val entry =
            MarkdownParser
                .parseNote(note)
                .blocks
                .footnotes()
                .single()
        val actions = RenderActions(inline = InlineActions(onWikiLink = { target, _ -> followed += target }))
        compose.setContent { FootnoteSheet(entry, actions, emptySet(), onDismiss = {}) }

        compose.onNodeWithText("Footnote 1").assertExists()
        compose.onNodeWithText("From Source Note.").performFirstLinkClick()
        assertThat(followed).containsExactly("Source Note")
    }
}
