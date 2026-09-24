package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrontMatterTest {
    private fun properties(markdown: String): List<FrontMatterProperty> =
        MarkdownParser
            .parseNote(markdown)
            .blocks
            .filterIsInstance<MdBlock.FrontMatter>()
            .single()
            .properties

    @Test
    fun `every shape a property comes in`() {
        val found =
            properties(
                """
                ---
                title: 'Quoted'
                my key: has a space
                created: 2024-01-02
                tags: [a, "b, c"]
                aliases:
                  - First
                  - "Second name"
                summary: |
                  one
                  two
                empty:
                ---
                Body
                """.trimIndent(),
            )
        assertThat(found)
            .containsExactly(
                FrontMatterProperty("title", listOf("Quoted")),
                FrontMatterProperty("my key", listOf("has a space")),
                FrontMatterProperty("created", listOf("2024-01-02")),
                FrontMatterProperty("tags", listOf("a", "b, c"), isList = true),
                FrontMatterProperty("aliases", listOf("First", "Second name"), isList = true),
                FrontMatterProperty("summary", listOf("one\ntwo")),
                FrontMatterProperty("empty", emptyList()),
            ).inOrder()
    }

    @Test
    fun `a list written flush against its key is still its list`() {
        assertThat(properties("---\naliases:\n- One\n- Two\nnext: x\n---\n"))
            .containsExactly(
                FrontMatterProperty("aliases", listOf("One", "Two"), isList = true),
                FrontMatterProperty("next", listOf("x")),
            ).inOrder()
    }

    @Test
    fun `a value with a colon in it keeps it`() {
        assertThat(properties("---\nurl: https://example.com/a\n---\n"))
            .containsExactly(FrontMatterProperty("url", listOf("https://example.com/a")))
    }

    @Test
    fun `aliases from a list, a string, or the singular key`() {
        assertThat(MarkdownParser.parseNote("---\naliases: [One, Two]\n---\n").aliases).containsExactly("One", "Two")
        assertThat(MarkdownParser.parseNote("---\naliases: One, Two\n---\n").aliases).containsExactly("One", "Two")
        assertThat(MarkdownParser.parseNote("---\nalias: Solo\n---\n").aliases).containsExactly("Solo")
        assertThat(MarkdownParser.parseNote("No front matter").aliases).isEmpty()
    }

    @Test
    fun `an unclosed fence has no properties`() {
        val blocks = MarkdownParser.parseNote("---\n\nNot front matter").blocks
        assertThat(blocks.filterIsInstance<MdBlock.FrontMatter>().flatMap { it.properties }).isEmpty()
    }
}
