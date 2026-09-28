package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.PrintedNote.Kind
import me.parham1995.notes.markdown.PrintedNote.Line
import org.junit.Test

/** A note as it goes onto paper. Synthetic. */
class PrintedNoteTest {
    private fun printed(markdown: String) = PrintedNote.of(MarkdownParser.parseNote(markdown).blocks)

    @Test
    fun `headings, prose and lists print with their kind and their markers`() {
        val lines =
            printed(
                """
                ---
                tags: [x]
                ---
                # Garden

                Water the **red** ones, see [[Tomato]].

                - [ ] buy seeds
                  - small pots
                1. first
                ![[photo.png]]
                """.trimIndent(),
            )

        assertThat(lines)
            .containsExactly(
                Line("Garden", Kind.HEADING, level = 1),
                Line("Water the red ones, see Tomato.", Kind.BODY),
                Line("☐ buy seeds", Kind.BODY),
                Line("• small pots", Kind.BODY, indent = 1),
                Line("1. first", Kind.BODY),
            ).inOrder()
    }

    @Test
    fun `quotes and callouts print as quoted, a folded callout only by its title`() {
        val lines = printed("> said once\n\n> [!note] Keep\n> inside\n\n> [!tip]- Folded\n> hidden")

        assertThat(lines)
            .containsExactly(
                Line("said once", Kind.QUOTE, indent = 1),
                Line("Keep", Kind.QUOTE, indent = 1),
                Line("inside", Kind.QUOTE, indent = 1),
                Line("Folded", Kind.QUOTE, indent = 1),
            ).inOrder()
    }

    @Test
    fun `code keeps its lines and a table prints a row at a time`() {
        val lines = printed("```\nfirst\n  second\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |")

        assertThat(lines.map { it.text to it.kind })
            .containsExactly(
                "first" to Kind.CODE,
                "  second" to Kind.CODE,
                "A  ·  B" to Kind.BODY,
                "1  ·  2" to Kind.BODY,
            ).inOrder()
    }
}
