package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a callout's first line says, beyond its kind. */
class CalloutTest {
    private fun callout(markdown: String): MdBlock.Callout =
        MarkdownParser
            .parseNote(markdown)
            .blocks
            .filterIsInstance<MdBlock.Callout>()
            .single()

    @Test
    fun `a title keeps its links, code and maths`() {
        val title = callout("> [!note] See [[Plan|the plan]] and `make` for ${'$'}x${'$'}\n> body").title
        assertThat(title.filterIsInstance<MdInline.WikiLink>().single().target).isEqualTo("Plan")
        assertThat(title.filterIsInstance<MdInline.Code>().single().code).isEqualTo("make")
        assertThat(title.filterIsInstance<MdInline.InlineMath>().single().latex).isEqualTo("x")
        assertThat(plainText(title)).isEqualTo("See the plan and make for x")
    }

    @Test
    fun `a title's link is one the index sees`() {
        val links = MarkdownParser.parseNote("> [!tip] Read [[Plan]] first\n> body").links
        assertThat(links.map { it.rawTarget }).contains("Plan")
    }

    @Test
    fun `plus is foldable and open, minus is foldable and shut`() {
        val open = callout("> [!tip]+ Open\n> body")
        assertThat(open.foldable).isTrue()
        assertThat(open.collapsed).isFalse()

        val shut = callout("> [!tip]- Shut\n> body")
        assertThat(shut.foldable).isTrue()
        assertThat(shut.collapsed).isTrue()

        val plain = callout("> [!tip] Plain\n> body")
        assertThat(plain.foldable).isFalse()
    }

    @Test
    fun `the written type is kept, so an unknown one can be named`() {
        val custom = callout("> [!recipe]\n> body")
        assertThat(custom.kind).isEqualTo(CalloutKind.NOTE)
        assertThat(custom.type).isEqualTo("recipe")
        assertThat(custom.title).isEmpty()
    }
}
