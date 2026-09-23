package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A paragraph holding something that cannot be a run of text.
 *
 * Display maths and embeds are blocks on the page, but Markdown only makes
 * them blocks when they stand alone. Written straight under a line of text --
 * which is how they are usually written -- they are part of that paragraph, and
 * anything the flattener did not lift out was either dropped or shown as the
 * link text it was written as.
 */
class ParagraphSplitTest {
    private val d = "$"

    private fun parse(markdown: String) = MarkdownParser.parseNote(markdown)

    private fun MdBlock.describe(): String =
        when (this) {
            is MdBlock.Paragraph -> "p:" + plainText(inlines).trim()
            is MdBlock.MathBlock -> "math:$latex"
            is MdBlock.Image -> "image:$path"
            is MdBlock.Attachment -> "attachment:$path"
            else -> this::class.simpleName.orEmpty()
        }

    @Test
    fun `display maths directly under a line of text is kept`() {
        val note = parse("The area is\n$d$d\n\\pi r^2\n$d$d\nfor a circle.")
        assertThat(note.blocks.map { it.describe() })
            .containsExactly("p:The area is", "math:\\pi r^2", "p:for a circle.")
            .inOrder()
        assertThat(note.hasMath).isTrue()
    }

    @Test
    fun `display maths inside a list item is kept`() {
        val note = parse("- where\n  $d${d}E = mc^2$d$d\n")
        val item =
            note.blocks
                .filterIsInstance<MdBlock.ListBlock>()
                .single()
                .items
                .single()
        assertThat(item.blocks.map { it.describe() }).containsExactly("p:where", "math:E = mc^2").inOrder()
    }

    @Test
    fun `two embeds on consecutive lines are two images`() {
        val note = parse("![[uploads/a.png]]\n![[uploads/b.png]]")
        assertThat(note.blocks.map { it.describe() })
            .containsExactly("image:uploads/a.png", "image:uploads/b.png")
            .inOrder()
    }

    @Test
    fun `an image with a caption line under it keeps both`() {
        val note = parse("![[uploads/a.png]]\n*Figure 1: the setup*")
        assertThat(note.blocks.map { it.describe() })
            .containsExactly("image:uploads/a.png", "p:Figure 1: the setup")
            .inOrder()
    }

    @Test
    fun `a markdown image inside a sentence is lifted out of it`() {
        val note = parse("See ![a diagram](uploads/x.png) for the layout.")
        assertThat(note.blocks.map { it.describe() })
            .containsExactly("p:See", "image:uploads/x.png", "p:for the layout.")
            .inOrder()
        assertThat((note.blocks[1] as MdBlock.Image).alt).isEqualTo("a diagram")
    }

    @Test
    fun `an attachment embed beside text is a card and the text stays`() {
        val note = parse("The contract: ![[uploads/contract.pdf]]")
        assertThat(note.blocks.map { it.describe() })
            .containsExactly("p:The contract:", "attachment:uploads/contract.pdf")
            .inOrder()
    }

    @Test
    fun `an ordinary wikilink stays in its sentence`() {
        val note = parse("See [[Other]] for more.")
        assertThat(note.blocks.map { it.describe() }).containsExactly("p:See Other for more.")
    }

    @Test
    fun `ids stay unique and increasing across a split`() {
        val note = parse("# Title\n\nabove\n$d${d}x$d$d\nbelow\n\n## Next")
        val ids = note.blocks.map { it.id }
        assertThat(ids).isInStrictOrder()
        assertThat(note.headings.map { it.blockIndex }).containsExactly(0, 4).inOrder()
    }
}
