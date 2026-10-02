package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a fold at a heading hides, and what it has to give back.
 *
 * The scroll targets the rest of the reader hands over -- the outline, a
 * `[[Note#Heading]]`, a find hit, the paragraph being read aloud -- are
 * positions in the full block list, so the translation to what is on screen
 * has to be exact or every one of them lands short.
 */
class HeadingFoldsTest {
    private fun blocks(markdown: String): List<MdBlock> = MarkdownParser.parseNote(markdown).blocks

    private val note =
        blocks(
            """
            # Title

            intro

            ## First

            one

            ### Deeper

            deep

            ## Second

            two
            """.trimIndent(),
        )
    // Positions: 0 Title, 1 intro, 2 First, 3 one, 4 Deeper, 5 deep, 6 Second, 7 two.

    @Test
    fun `a section runs to the next heading of its own rank or higher`() {
        assertThat(HeadingFolds.sectionEnd(note, 2)).isEqualTo(6)
        assertThat(HeadingFolds.sectionEnd(note, 4)).isEqualTo(6)
        assertThat(HeadingFolds.sectionEnd(note, 6)).isEqualTo(8)
        assertThat(HeadingFolds.sectionEnd(note, 0)).isEqualTo(8)
    }

    @Test
    fun `a paragraph owns nothing`() {
        assertThat(HeadingFolds.sectionEnd(note, 1)).isEqualTo(2)
        assertThat(HeadingFolds.hasSection(note, 1)).isFalse()
    }

    @Test
    fun `a heading with nothing under it cannot fold`() {
        val tail = blocks("# One\n\ntext\n\n# Two\n")
        assertThat(HeadingFolds.hasSection(tail, 0)).isTrue()
        assertThat(HeadingFolds.hasSection(tail, 2)).isFalse()
    }

    @Test
    fun `folding keeps the heading and drops its section, nested headings included`() {
        assertThat(HeadingFolds.visible(note, setOf(2))).containsExactly(0, 1, 2, 6, 7).inOrder()
    }

    @Test
    fun `a fold inside a fold is hidden with the section and remembered`() {
        val both = setOf(2, 4)
        assertThat(HeadingFolds.visible(note, both)).containsExactly(0, 1, 2, 6, 7).inOrder()
        // Unfolding the outer one brings the inner one back, still folded.
        assertThat(HeadingFolds.visible(note, setOf(4))).containsExactly(0, 1, 2, 3, 4, 6, 7).inOrder()
    }

    @Test
    fun `nothing folded shows everything`() {
        assertThat(HeadingFolds.visible(note, emptySet())).isEqualTo(note.indices.toList())
    }

    @Test
    fun `revealing a hidden block opens every fold over it`() {
        val opened = HeadingFolds.revealing(note, setOf(2, 4, 6), target = 5)
        assertThat(opened).containsExactly(6)
        assertThat(HeadingFolds.visible(note, opened).indexOf(5)).isEqualTo(5)
    }

    @Test
    fun `revealing a folded heading leaves it folded`() {
        // The heading is on screen already; what it hides was not asked for.
        assertThat(HeadingFolds.revealing(note, setOf(2), target = 2)).containsExactly(2)
    }

    @Test
    fun `a stored fold that no longer names a section is dropped`() {
        // 1 is a paragraph, 6 is Second, 99 is off the end of the note.
        assertThat(HeadingFolds.kept(note, listOf(2, 1, 6, 99))).containsExactly(2, 6).inOrder()
    }

    @Test
    fun `a position with folds above it moves up on screen`() {
        // Second is block 6, but with First folded it is the fourth thing drawn.
        assertThat(HeadingFolds.visible(note, setOf(2)).indexOf(6)).isEqualTo(3)
    }
}
