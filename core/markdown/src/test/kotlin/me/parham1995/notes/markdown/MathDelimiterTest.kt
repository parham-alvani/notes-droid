package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When a dollar sign opens a formula and when it is money.
 *
 * Obsidian's rule, which is Pandoc's: an opening `$` is followed by something
 * other than whitespace, a closing one is preceded by something other than
 * whitespace, and a closing one is not followed by a digit. Anything short of
 * that turned prices into formulas.
 */
class MathDelimiterTest {
    private val d = "$"

    private fun inlineMath(markdown: String): List<String> =
        MarkdownParser
            .parseNote(markdown)
            .blocks
            .filterIsInstance<MdBlock.Paragraph>()
            .flatMap { it.inlines }
            .filterIsInstance<MdInline.InlineMath>()
            .map { it.latex }

    @Test
    fun `two prices in one sentence are not a formula`() {
        val note = MarkdownParser.parseNote("It went from ${d}5 and ${d}10 in a week.")
        assertThat(note.hasMath).isFalse()
        assertThat(plainText((note.blocks.single() as MdBlock.Paragraph).inlines))
            .isEqualTo("It went from ${d}5 and ${d}10 in a week.")
    }

    @Test
    fun `a closing dollar followed by a digit does not close`() {
        assertThat(inlineMath("between ${d}x${d}5 and more")).isEmpty()
    }

    @Test
    fun `whitespace inside either delimiter is not maths`() {
        assertThat(inlineMath("a $d x$d b")).isEmpty()
        assertThat(inlineMath("a ${d}x $d b")).isEmpty()
    }

    @Test
    fun `a formula after a price still parses`() {
        // The first dollar cannot close anywhere, so the scan moves on and the
        // real formula after it is still found.
        assertThat(inlineMath("costs ${d}5, and ${d}x^2$d is a curve")).containsExactly("x^2")
    }

    @Test
    fun `ordinary inline maths is unaffected`() {
        assertThat(inlineMath("where ${d}a + b$d, and ${d}c$d.")).containsExactly("a + b", "c")
    }
}
