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
                // Written on the line under the item, so it is part of it.
                Line("", Kind.IMAGE, indent = 1, image = "photo.png"),
            ).inOrder()
    }

    @Test
    fun `quotes and callouts print as quoted, a folded callout only by its title`() {
        val lines = printed("> said once\n\n> [!note] Keep\n> inside\n\n> [!tip]- Folded\n> hidden")

        assertThat(lines)
            .containsExactly(
                Line("said once", Kind.QUOTE, indent = 1),
                Line("Keep", Kind.QUOTE, indent = 1, callout = CalloutKind.NOTE),
                Line("inside", Kind.QUOTE, indent = 1, callout = CalloutKind.NOTE),
                Line("Folded", Kind.QUOTE, indent = 1, callout = CalloutKind.TIP),
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

    @Test
    fun `a first heading that says the title again is left out, the head has it`() {
        val blocks = MarkdownParser.parseNote("---\ntags: [x]\n---\n# Garden\n\nWater.\n\n# Garden\n").blocks

        assertThat(PrintedNote.of(blocks, title = "Garden"))
            .containsExactly(Line("Water.", Kind.BODY), Line("Garden", Kind.HEADING, level = 1))
            .inOrder()
        assertThat(PrintedNote.of(blocks, title = "Orchard").first()).isEqualTo(Line("Garden", Kind.HEADING, level = 1))
    }

    @Test
    fun `tasks keep their status, done and cancelled greyed, and signs become words`() {
        val lines = printed("- [ ] Call 📅 2026-11-02 ⏫\n- [x] Paid ✅ 2026-10-30\n- [-] Dropped\n- [/] Halfway")

        assertThat(lines)
            .containsExactly(
                Line("☐ Call due 2026-11-02 high", Kind.BODY),
                Line("☑ Paid done 2026-10-30", Kind.BODY, faded = true),
                Line("☒ Dropped", Kind.BODY, faded = true),
                Line("☐ Halfway", Kind.BODY),
            ).inOrder()
    }

    @Test
    fun `a picture of the vault is printed at the width it asked for, one from the web is not fetched`() {
        val lines =
            printed(
                "![[uploads/plan.png|300]]\n\n![chart](https://example.com/c.png)\n\n![dot](data:image/png;base64,AAAA)",
            )

        assertThat(lines).containsExactly(Line("", Kind.IMAGE, image = "uploads/plan.png", width = 300))
    }
}
