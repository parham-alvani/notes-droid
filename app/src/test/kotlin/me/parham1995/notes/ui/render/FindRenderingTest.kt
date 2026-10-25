package me.parham1995.notes.ui.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.NoteSearch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Find in the note marks its hits on the page: every one tinted, the current
 * one solid. It used to only scroll to the block, which in a long paragraph
 * left the word to be found a second time by eye.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FindRenderingTest {
    @get:Rule
    val compose = createComposeRule()

    private val note = "# Pond\n\nA heron by the pond, and a pond beyond it.\n\nNothing here.\n"
    private val blocks = MarkdownParser.parseNote(note).blocks
    private val matches = NoteSearch.find(blocks, "pond")

    private var primary = Color.Unspecified

    private fun show(target: FindTarget?) {
        compose.setContent {
            primary = MaterialTheme.colorScheme.primary
            MarkdownDocument(blocks = blocks, actions = RenderActions(), find = target)
        }
    }

    private fun text(substring: String): AnnotatedString =
        compose
            .onNodeWithText(substring, substring = true)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()

    /** The spans with a background, as the text they cover and whether they are the current one. */
    private fun marks(text: AnnotatedString): List<Pair<String, Boolean>> =
        text.spanStyles
            .filter { it.item.background != Color.Unspecified }
            .sortedBy { it.start }
            .map { text.text.substring(it.start, it.end) to (it.item.background == primary) }

    @Test
    fun `every hit is marked, and only the one the bar is on is current`() {
        // The heading holds the first hit; the paragraph the second and third.
        assertThat(matches.map { it.blockIndex }.distinct()).hasSize(2)
        val paragraph = matches[1].blockIndex
        show(FindTarget("pond", block = paragraph, ordinal = 1))

        assertThat(marks(text("A heron"))).containsExactly("pond" to false, "pond" to true).inOrder()
        assertThat(marks(text("Pond"))).containsExactly("Pond" to false)
        assertThat(marks(text("Nothing here"))).isEmpty()
    }

    @Test
    fun `with find closed nothing is marked`() {
        show(null)

        assertThat(marks(text("A heron"))).isEmpty()
        assertThat(marks(text("Pond"))).isEmpty()
    }
}
