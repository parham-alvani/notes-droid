package me.parham1995.notes.ui.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.MdBlock
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

    @Test
    fun `in a list only the item the bar is on is current`() {
        val list = "- a heron\n- no birds\n- a pond heron\n    - heron nest\n"
        val listBlocks = MarkdownParser.parseNote(list).blocks
        val hits = NoteSearch.find(listBlocks, "heron")
        assertThat(hits).hasSize(3)
        compose.setContent {
            primary = MaterialTheme.colorScheme.primary
            MarkdownDocument(
                blocks = listBlocks,
                actions = RenderActions(),
                find = FindTarget("heron", hits[2].blockIndex, 2),
            )
        }

        assertThat(marks(text("a heron"))).containsExactly("heron" to false)
        assertThat(marks(text("a pond heron"))).containsExactly("heron" to false)
        assertThat(marks(text("heron nest"))).containsExactly("heron" to true)
    }

    @Test
    fun `a list hands the current hit to the block that holds it`() {
        val list = MarkdownParser.parseNote("- pond\n- pond and pond\n- dry\n").blocks.single() as MdBlock.ListBlock

        val marks = FindMarks("pond", current = 2).forItems(list)

        assertThat(marks.map { item -> item.map { it.current } })
            .containsExactly(listOf(null), listOf(1), listOf(null))
            .inOrder()
    }

    @Test
    fun `the page is told where a hit far down a long list is drawn`() {
        val list = (1..80).joinToString("") { "- item $it${if (it == 70) " heron" else ""}\n" }
        val listBlocks = MarkdownParser.parseNote(list).blocks
        val hit = NoteSearch.find(listBlocks, "heron").single()
        var told: Pair<Float, Float>? = null
        compose.setContent {
            MarkdownDocument(
                blocks = listBlocks,
                actions = RenderActions(),
                find = FindTarget("heron", hit.blockIndex, 0),
                onFindHit = { top, bottom -> told = top to bottom },
            )
        }
        compose.waitForIdle()

        // The list's top is on screen; the hit is some 69 lines below it.
        val (top, bottom) = checkNotNull(told)
        assertThat(top).isGreaterThan(1500f)
        assertThat(bottom).isGreaterThan(top)
    }
}
