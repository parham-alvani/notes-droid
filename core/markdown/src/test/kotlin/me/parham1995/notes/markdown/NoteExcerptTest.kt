package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The plain text a pinned note's card shows.
 *
 * The card is a single text view with no renderer behind it, so anything the
 * excerpt lets through is drawn literally: a missed case is a `##` or a `- [ ]`
 * on somebody's home screen.
 */
class NoteExcerptTest {
    private fun excerpt(
        markdown: String,
        lines: Int = NoteExcerpt.DEFAULT_LINES,
        title: String? = null,
    ) = NoteExcerpt.lines(markdown.trimIndent(), lines, title)

    @Test
    fun `headings lose their hashes`() {
        assertThat(excerpt("# One\n\n## Two\n\n###### Six")).containsExactly("One", "Two", "Six").inOrder()
    }

    @Test
    fun `a leading heading that repeats the title is not said twice`() {
        assertThat(excerpt("# Plans\n\nFirst line", title = "plans")).containsExactly("First line")
        // Only at the top: further down it is a section, and belongs.
        assertThat(excerpt("Intro\n\n# Plans", title = "Plans")).containsExactly("Intro", "Plans").inOrder()
        // And only when it is the title; any other heading stays.
        assertThat(excerpt("# Other\n\nbody", title = "Plans")).containsExactly("Other", "body").inOrder()
    }

    @Test
    fun `list items are bulleted, numbered lists keep their numbers`() {
        assertThat(excerpt("- apples\n- pears")).containsExactly("• apples", "• pears").inOrder()
        assertThat(excerpt("3. third\n4. fourth")).containsExactly("3. third", "4. fourth").inOrder()
    }

    @Test
    fun `nested items are indented under their parent`() {
        assertThat(excerpt("- parent\n  - child\n    - grandchild"))
            .containsExactly("• parent", "  • child", "    • grandchild")
            .inOrder()
    }

    @Test
    fun `tasks are boxes, ticked or not`() {
        assertThat(
            excerpt(
                """
                - [ ] open
                - [x] done
                - [/] going
                - [-] dropped
                """,
            ),
        ).containsExactly("☐ open", "☑ done", "☐ going", "☑ dropped").inOrder()
    }

    @Test
    fun `a task keeps its words and loses the plugin's emoji dates`() {
        assertThat(excerpt("- [ ] call the bank 📅 2026-01-02")).containsExactly("☐ call the bank")
    }

    @Test
    fun `links read as the words they were written with`() {
        assertThat(excerpt("See [the docs](https://example.com) now")).containsExactly("See the docs now")
    }

    @Test
    fun `wikilinks read as their alias, or the note they name`() {
        assertThat(excerpt("Ask [[People/Sam|Sam]] about [[Project Plan]] and [[Folder/Deep Note]]"))
            .containsExactly("Ask Sam about Project Plan and Deep Note")
    }

    @Test
    fun `emphasis, code and highlights are just their words`() {
        assertThat(excerpt("**bold** _it_ ~~gone~~ ==marked== `code`"))
            .containsExactly("bold it gone marked code")
    }

    @Test
    fun `front matter is left out`() {
        assertThat(
            excerpt(
                """
                ---
                tags: [a, b]
                created: 2026-01-01
                ---
                # Title
                Body
                """,
                title = "Title",
            ),
        ).containsExactly("Body")
    }

    @Test
    fun `a callout is its title then its body`() {
        assertThat(excerpt("> [!warning] Mind the gap\n> it is wide"))
            .containsExactly("Mind the gap", "it is wide")
            .inOrder()
    }

    @Test
    fun `an untitled callout is named after its type`() {
        assertThat(excerpt("> [!tip]\n> a hint")).containsExactly("Tip", "a hint").inOrder()
    }

    @Test
    fun `a folded callout shows only its title`() {
        assertThat(excerpt("> [!note]- Hidden\n> secret")).containsExactly("Hidden")
    }

    @Test
    fun `a quote is its words`() {
        assertThat(excerpt("> quoted\n> text")).containsExactly("quoted", "text").inOrder()
    }

    @Test
    fun `code is shown without its fences`() {
        assertThat(excerpt("```kotlin\nval x = 1\nval y = 2\n```"))
            .containsExactly("val x = 1", "val y = 2")
            .inOrder()
    }

    @Test
    fun `what cannot be read as text is left out`() {
        val lines =
            excerpt(
                """
                ```mermaid
                graph TD; A-->B
                ```

                ---

                ${'$'}${'$'}
                x^2
                ${'$'}${'$'}

                ![[photo.png]]

                ```dataview
                LIST
                ```

                after
                """,
            )
        assertThat(lines).containsExactly("after")
    }

    @Test
    fun `soft line breaks start a new line, as Obsidian draws them`() {
        assertThat(excerpt("first\nsecond")).containsExactly("first", "second").inOrder()
    }

    @Test
    fun `blank lines are dropped`() {
        assertThat(excerpt("one\n\n\n\ntwo")).containsExactly("one", "two").inOrder()
    }

    @Test
    fun `it stops at the line limit`() {
        val lines = excerpt((1..20).joinToString("\n\n") { "line $it" }, lines = 3)
        assertThat(lines).containsExactly("line 1", "line 2", "line 3").inOrder()
    }

    @Test
    fun `the limit counts lines inside lists and callouts too`() {
        val lines = excerpt((1..20).joinToString("\n") { "- item $it" }, lines = 2)
        assertThat(lines).containsExactly("• item 1", "• item 2").inOrder()
    }

    @Test
    fun `one enormous paragraph is cut, not carried whole`() {
        val line = excerpt("word ".repeat(500)).single()
        assertThat(line.length).isAtMost(NoteExcerpt.MAX_LINE_CHARS)
        assertThat(line).endsWith("…")
    }

    @Test
    fun `persian text is kept whole and in order`() {
        assertThat(
            excerpt(
                """
                # سلام دنیا

                - [ ] خرید نان
                - مورد [[یادداشت|پیوند]]
                """,
            ),
        ).containsExactly("سلام دنیا", "☐ خرید نان", "• مورد پیوند").inOrder()
    }

    @Test
    fun `an empty note has nothing to say`() {
        assertThat(excerpt("")).isEmpty()
        assertThat(excerpt("---\na: b\n---\n")).isEmpty()
    }

    @Test
    fun `text is the lines, one per line`() {
        assertThat(NoteExcerpt.text("# A\n\nb\n- c")).isEqualTo("A\nb\n• c")
    }
}
