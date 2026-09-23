package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What an `![[embed]]` is, beyond a picture.
 *
 * Obsidian overloads the alias: after the pipe of an image it is a size, not a
 * caption. And an embed with no file extension is another note, which Obsidian
 * draws in place -- it was being offered to "another app", which had nothing
 * to open.
 */
class EmbedTest {
    private fun single(markdown: String): MdBlock = MarkdownParser.parseNote(markdown).blocks.single()

    @Test
    fun `a width after the pipe is a size, not a caption`() {
        val image = single("![[uploads/a.png|300]]") as MdBlock.Image
        assertThat(image.alt).isNull()
        assertThat(image.width).isEqualTo(300)
        assertThat(image.height).isNull()
    }

    @Test
    fun `width and height are both read`() {
        val image = single("![[uploads/a.png|300x200]]") as MdBlock.Image
        assertThat(image.alt).isNull()
        assertThat(image.width).isEqualTo(300)
        assertThat(image.height).isEqualTo(200)
    }

    @Test
    fun `a caption and a size can be given together`() {
        val image = single("![[uploads/a.png|the setup|300]]") as MdBlock.Image
        assertThat(image.alt).isEqualTo("the setup")
        assertThat(image.width).isEqualTo(300)
    }

    @Test
    fun `a markdown image takes its size from the alt text too`() {
        val image = single("![a chart|240](uploads/chart.png)") as MdBlock.Image
        assertThat(image.alt).isEqualTo("a chart")
        assertThat(image.width).isEqualTo(240)
    }

    @Test
    fun `a caption that is only words stays a caption`() {
        val image = single("![[uploads/a.png|Figure 2]]") as MdBlock.Image
        assertThat(image.alt).isEqualTo("Figure 2")
        assertThat(image.width).isNull()
    }

    @Test
    fun `an embed of a note is a note embed, not an attachment`() {
        val embed = single("![[Projects/Plan#Open items]]") as MdBlock.NoteEmbed
        assertThat(embed.target).isEqualTo("Projects/Plan")
        assertThat(embed.heading).isEqualTo("Open items")
        assertThat(embed.label).isEqualTo("Plan")
    }

    @Test
    fun `a name with a dot in it is still a note`() {
        assertThat(single("![[Release v1.2 notes]]")).isInstanceOf(MdBlock.NoteEmbed::class.java)
        assertThat(single("![[Plan.md]]")).isInstanceOf(MdBlock.NoteEmbed::class.java)
    }

    @Test
    fun `a file is still an attachment`() {
        assertThat(single("![[uploads/contract.pdf]]")).isInstanceOf(MdBlock.Attachment::class.java)
    }

    @Test
    fun `a note embed is a link the index can resolve`() {
        val links = MarkdownParser.parseNote("![[Plan#Open items]]").links
        assertThat(links.map { it.kind to it.rawTarget }).containsExactly(LinkKind.WIKI_EMBED to "Plan")
        assertThat(links.single().heading).isEqualTo("Open items")
    }

    // -- the section a heading embed means ------------------------------------

    private val note =
        MarkdownParser
            .parseNote(
                """
                intro

                ## Open items

                - one

                ### Detail

                more

                ## Done

                finished
                """.trimIndent(),
            ).blocks

    @Test
    fun `a heading embed is that heading and everything under it`() {
        val section = Transclusion.section(note, "open items")!!
        assertThat(section.first()).isInstanceOf(MdBlock.Heading::class.java)
        assertThat(section.filterIsInstance<MdBlock.Heading>().map { it.text })
            .containsExactly("Open items", "Detail")
            .inOrder()
        assertThat(section.last()).isInstanceOf(MdBlock.Paragraph::class.java)
    }

    @Test
    fun `no heading means the whole note, and an unknown one means nothing`() {
        assertThat(Transclusion.section(note, null)).isEqualTo(note)
        assertThat(Transclusion.section(note, "Renamed long ago")).isNull()
    }
}
