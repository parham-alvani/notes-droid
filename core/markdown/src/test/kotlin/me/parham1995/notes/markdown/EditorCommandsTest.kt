package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The format bar's buttons: `look.md` in notes-linux's `docs/specs/`, "The format bar". */
class EditorCommandsTest {
    /** Runs [command] on [text], where `|` marks the two ends of the selection, and marks them in what comes back. */
    private fun run(
        text: String,
        command: (String, Int, Int) -> EditorEdit,
    ): String {
        val start = text.indexOf('|')
        val end = text.lastIndexOf('|') - 1
        val edit = command(text.replace("|", ""), start, end)
        return StringBuilder(edit.text).insert(edit.end, '|').insert(edit.start, '|').toString()
    }

    private fun wrap(
        text: String,
        mark: String,
    ) = run(text) { note, start, end -> EditorCommands.wrap(note, start, end, mark) }

    @Test
    fun `a button wraps the selection and keeps it on the same words`() {
        assertThat(wrap("a |word| here", EditorCommands.BOLD)).isEqualTo("a **|word|** here")
        assertThat(wrap("a |word| here", EditorCommands.ITALIC)).isEqualTo("a *|word|* here")
        assertThat(wrap("a |word| here", EditorCommands.HIGHLIGHT)).isEqualTo("a ==|word|== here")
        assertThat(wrap("a |word| here", EditorCommands.CODE)).isEqualTo("a `|word|` here")
        assertThat(wrap("a |word| here", EditorCommands.STRIKE)).isEqualTo("a ~~|word|~~ here")
    }

    @Test
    fun `a second press takes the marks off`() {
        for (mark in listOf(
            EditorCommands.BOLD,
            EditorCommands.ITALIC,
            EditorCommands.HIGHLIGHT,
            EditorCommands.CODE,
            EditorCommands.STRIKE,
        )) {
            val once = wrap("a |word| here", mark)
            assertThat(wrap(once, mark)).isEqualTo("a |word| here")
        }
        // With the marks in the selection, they come off too.
        assertThat(wrap("a |**word**| here", EditorCommands.BOLD)).isEqualTo("a |word| here")
        assertThat(wrap("a |==word==| here", EditorCommands.HIGHLIGHT)).isEqualTo("a |word| here")
    }

    @Test
    fun `bold and italic are told apart by their stars`() {
        // Italic on bold text adds a star; it does not take one of bold's.
        assertThat(wrap("**|word|**", EditorCommands.ITALIC)).isEqualTo("***|word|***")
        assertThat(wrap("***|word|***", EditorCommands.ITALIC)).isEqualTo("**|word|**")
        assertThat(wrap("***|word|***", EditorCommands.BOLD)).isEqualTo("*|word|*")
        assertThat(wrap("*|word|*", EditorCommands.BOLD)).isEqualTo("***|word|***")
        assertThat(wrap("|**word**|", EditorCommands.ITALIC)).isEqualTo("*|**word**|*")
    }

    @Test
    fun `a selection at the note's ends is wrapped too`() {
        assertThat(wrap("|all|", EditorCommands.BOLD)).isEqualTo("**|all|**")
        assertThat(wrap("**|all|**", EditorCommands.BOLD)).isEqualTo("|all|")
    }

    @Test
    fun `a link to a note is the selection in two brackets, and a second press takes them off`() {
        val linked = run("see |Plan| now", EditorCommands::link)
        assertThat(linked).isEqualTo("see [[|Plan|]] now")
        assertThat(run(linked, EditorCommands::link)).isEqualTo("see |Plan| now")
        assertThat(run("|Plan|", EditorCommands::link)).isEqualTo("[[|Plan|]]")
    }
}
