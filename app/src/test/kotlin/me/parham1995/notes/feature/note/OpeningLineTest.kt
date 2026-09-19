package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.MdInline
import org.junit.Test

/**
 * What a link preview shows of the note it points at.
 *
 * Found on the device: previewing a link showed "Related notes:", which is how
 * a great many notes in this vault open and says nothing about any of them.
 */
class OpeningLineTest {
    private fun para(text: String) = MdBlock.Paragraph(id = 0, inlines = listOf(MdInline.Text(text)))

    @Test
    fun `a label ending in a colon is passed over`() {
        val opening =
            openingLine(
                listOf(
                    para("Related notes:"),
                    para("Change data capture turns a database's own write log into a stream of events."),
                ),
            )

        assertThat(opening).startsWith("Change data capture turns")
    }

    @Test
    fun `a short line is passed over even without a colon`() {
        val opening =
            openingLine(listOf(para("See also"), para("A long enough sentence to be the actual opening of a note.")))

        assertThat(opening).startsWith("A long enough sentence")
    }

    @Test
    fun `prose is taken as it stands`() {
        val text = "An architectural pattern that splits the model used to update a system."
        assertThat(openingLine(listOf(para(text)))).isEqualTo(text)
    }

    @Test
    fun `a note of nothing but labels still previews something`() {
        // Better a poor preview than an empty sheet.
        assertThat(openingLine(listOf(para("Related notes:")))).isEqualTo("Related notes:")
    }

    @Test
    fun `a note with no paragraphs at all previews nothing`() {
        assertThat(openingLine(listOf(MdBlock.ThematicBreak(id = 0)))).isEmpty()
    }

    @Test
    fun `a very long opening is cut rather than shown whole`() {
        val opening = openingLine(listOf(para("word ".repeat(200))))
        assertThat(opening.length).isAtMost(240)
    }
}
