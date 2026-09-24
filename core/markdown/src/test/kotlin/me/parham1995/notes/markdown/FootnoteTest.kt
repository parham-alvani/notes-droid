package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FootnoteTest {
    private fun parse(markdown: String): ParsedNote = MarkdownParser.parseNote(markdown)

    private fun MdBlock.refs(): List<MdInline.FootnoteRef> =
        (this as MdBlock.Paragraph).inlines.filterIsInstance<MdInline.FootnoteRef>()

    private fun FootnoteEntry.text(): String =
        blocks.filterIsInstance<MdBlock.Paragraph>().joinToString(" ") { plainText(it.inlines) }

    @Test
    fun `a reference is numbered by first use and its definition gathered at the end`() {
        val note =
            parse(
                """
                First[^b] then second[^a] and first again[^b].

                [^a]: The a note.
                [^b]: The b note, with a [[Link]].

                Last paragraph.
                """.trimIndent(),
            )

        val refs = note.blocks.first().refs()
        assertThat(refs.map { it.label to it.number }).containsExactly("b" to 1, "a" to 2, "b" to 1).inOrder()

        // The definitions are not paragraphs where they were written.
        assertThat(note.blocks.filterIsInstance<MdBlock.Paragraph>()).hasSize(2)
        val footnotes = note.blocks.last() as MdBlock.Footnotes
        assertThat(footnotes.entries.map { it.number to it.text() })
            .containsExactly(1 to "The b note, with a Link.", 2 to "The a note.")
            .inOrder()
        // A link in a footnote is a link like any other, for backlinks too.
        assertThat(note.links.map { it.rawTarget }).contains("Link")
    }

    @Test
    fun `an inline footnote is a footnote of its own and not part of the sentence`() {
        val note = parse("A claim^[Said by someone.] and more.")

        val paragraph = note.blocks.first() as MdBlock.Paragraph
        assertThat(plainText(paragraph.inlines)).isEqualTo("A claim and more.")
        val entry = note.blocks.footnotes().single()
        assertThat(entry.number).isEqualTo(1)
        assertThat(entry.text()).isEqualTo("Said by someone.")
        // Still searchable.
        assertThat(note.plainText).contains("Said by someone.")
    }

    @Test
    fun `a reference with no definition stays as it was written`() {
        val note = parse("Dangling[^nope].")
        assertThat(plainText((note.blocks.single() as MdBlock.Paragraph).inlines)).isEqualTo("Dangling[^nope].")
        assertThat(note.blocks.footnotes()).isEmpty()
    }

    @Test
    fun `an uncited definition is not shown`() {
        val note = parse("Text.\n\n[^unused]: Nobody cites this.")
        assertThat(note.blocks.filterIsInstance<MdBlock.Footnotes>()).isEmpty()
    }

    @Test
    fun `the last section embedded alone does not carry the whole note's footnotes`() {
        val note = parse("# One\n\nText[^1].\n\n## Last\n\nfinal words\n\n[^1]: Note.")
        val section = Transclusion.section(note.blocks, "Last")!!
        assertThat(section.filterIsInstance<MdBlock.Footnotes>()).isEmpty()
        assertThat(section).hasSize(2)
    }

    @Test
    fun `headings keep their place when footnotes are gathered`() {
        val note = parse("# One\n\nText[^1].\n\n[^1]: Note.\n\n## Two\n\nmore")
        val two = note.headings.single { it.text == "Two" }
        assertThat((note.blocks[two.blockIndex] as MdBlock.Heading).text).isEqualTo("Two")
    }
}
