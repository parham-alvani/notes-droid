package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The inline HTML a note uses for formatting, read as that formatting. */
class InlineHtmlTest {
    private fun inlines(markdown: String): List<MdInline> =
        (MarkdownParser.parseNote(markdown).blocks.single() as MdBlock.Paragraph).inlines

    @Test
    fun `formatting tags are read as formatting`() {
        assertThat(inlines("a <mark>marked</mark>, <b>bold <i>both</i></b>, <kbd>Ctrl</kbd> and <del>gone</del>\n"))
            .containsExactly(
                MdInline.Text("a "),
                MdInline.Highlight(listOf(MdInline.Text("marked"))),
                MdInline.Text(", "),
                MdInline.Strong(listOf(MdInline.Text("bold "), MdInline.Emphasis(listOf(MdInline.Text("both"))))),
                MdInline.Text(", "),
                MdInline.Code("Ctrl"),
                MdInline.Text(" and "),
                MdInline.Strikethrough(listOf(MdInline.Text("gone"))),
            ).inOrder()
    }

    @Test
    fun `a tag left open, or one that is not formatting, is dropped and its words kept`() {
        val text =
            inlines("<mark>never closed and <u>under</u> x<sup>2</sup>\n").joinToString("") {
                (it as MdInline.Text).text
            }
        assertThat(text).isEqualTo("never closed and under x2")
    }

    @Test
    fun `a line break is still a line break`() {
        assertThat(inlines("one<br>two\n")).contains(MdInline.LineBreak)
    }
}
