package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A heading's recorded position has to be where it is in the rendered list.
 *
 * The outline scrolls with it, and so does a `[[Note#Heading]]` link. It was
 * recorded as the heading's block *id*, and ids are handed out to nested
 * blocks too -- every list item, every line inside a callout -- so the moment
 * a note contains a list the id runs ahead of the position and the scroll
 * lands somewhere else, or off the end.
 */
class HeadingBlockIndexTest {
    private fun parse(markdown: String): ParsedNote = BlockFlattener().flatten(MarkdownParser.parse(markdown))

    @Test
    fun `a heading after nothing is at the top`() {
        val note = parse("# One\n\ntext\n")
        assertThat(note.headings.single().blockIndex).isEqualTo(0)
    }

    @Test
    fun `a heading after a list is still where the list says it is`() {
        val note =
            parse(
                """
                ## First

                - one
                - two
                - three

                ## Second

                text
                """.trimIndent(),
            )

        val second = note.headings.first { it.text == "Second" }
        assertThat(note.blocks[second.blockIndex]).isInstanceOf(MdBlock.Heading::class.java)
        assertThat((note.blocks[second.blockIndex] as MdBlock.Heading).text).isEqualTo("Second")
    }

    @Test
    fun `every heading indexes the block it actually is`() {
        val note =
            parse(
                """
                # Title

                - a
                - b

                ## Payload

                Some prose.

                > [!note]
                >
                > A callout with words in it.

                ### Deeper

                More prose.
                """.trimIndent(),
            )

        note.headings.forEach { heading ->
            val block = note.blocks.getOrNull(heading.blockIndex)
            assertThat(block).isInstanceOf(MdBlock.Heading::class.java)
            assertThat((block as MdBlock.Heading).text).isEqualTo(heading.text)
        }
    }
}
