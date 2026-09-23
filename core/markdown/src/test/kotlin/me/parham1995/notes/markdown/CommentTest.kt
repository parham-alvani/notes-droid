package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Obsidian's `%%comments%%`, which are for the author and never for the reader.
 *
 * They were shown as text, percent signs and all -- so every note-to-self in
 * the vault was on the page, which is the one place the author wrote them so
 * they would not be.
 */
class CommentTest {
    private fun parse(markdown: String) = MarkdownParser.parseNote(markdown)

    private fun ParsedNote.paragraphs(): List<String> =
        blocks.filterIsInstance<MdBlock.Paragraph>().map { plainText(it.inlines).trim() }

    @Test
    fun `an inline comment is hidden and the sentence around it kept`() {
        val note = parse("Before %%a private aside%% after.")
        assertThat(note.paragraphs()).containsExactly("Before  after.")
        assertThat(note.plainText).doesNotContain("private")
    }

    @Test
    fun `a comment spanning several lines and paragraphs is hidden entirely`() {
        val note =
            parse(
                """
                Kept above.

                %%
                A draft paragraph.

                - a list inside it
                %%

                Kept below.
                """.trimIndent(),
            )
        assertThat(note.paragraphs()).containsExactly("Kept above.", "Kept below.")
        assertThat(note.blocks.filterIsInstance<MdBlock.ListBlock>()).isEmpty()
    }

    @Test
    fun `formatting inside a comment goes with it`() {
        val note = parse("Shown %%hidden **bold** [[Link]]%% shown again.")
        assertThat(note.paragraphs()).containsExactly("Shown  shown again.")
        assertThat(note.links).isEmpty()
    }

    @Test
    fun `percent signs in code are not a comment`() {
        val note = parse("Use `%%` to escape, and %%this is hidden%%.\n\n```mermaid\n%% a mermaid comment\ngraph TD\n```")
        assertThat(note.paragraphs()).containsExactly("Use %% to escape, and .")
        assertThat(note.blocks.filterIsInstance<MdBlock.Mermaid>().single().code).contains("%% a mermaid comment")
    }

    @Test
    fun `an unclosed marker is left alone rather than hiding the rest of the note`() {
        val note = parse("Growth of 50%% this year.\n\nThe next paragraph.")
        assertThat(note.paragraphs()).containsExactly("Growth of 50%% this year.", "The next paragraph.")
    }

    @Test
    fun `a task line keeps its source line when a comment sits above it`() {
        val note = parse("%%\nnote to self\n%%\n\n- [ ] the task\n")
        val item =
            note.blocks
                .filterIsInstance<MdBlock.ListBlock>()
                .single()
                .items
                .single()
        assertThat(item.line).isEqualTo(4)
    }
}
