package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

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
    fun `a selection that starts in the middle of bold's stars does not make bold italic`() {
        // Half of `**` is not italic's star: the run is counted whole, inside the selection and outside it.
        val once = wrap("*|*word*|*", EditorCommands.ITALIC)
        assertThat(once).isEqualTo("**|*word*|**")
        assertThat(wrap(once, EditorCommands.ITALIC)).isEqualTo("*|*word*|*")
        // Bold is there, so bold comes off: the two stars next to each end of the selection.
        assertThat(wrap("*|*word*|*", EditorCommands.BOLD)).isEqualTo("|word|")
        assertThat(wrap("=|=word=|=", EditorCommands.HIGHLIGHT)).isEqualTo("|word|")
    }

    @Test
    fun `a button takes out nothing but its own marks, and a second press gives the text back`() {
        val parts = listOf("a", "b", " ", "*", "**", "=", "==", "`", "~~", "~", "[[", "]]", "س", "\uD83D\uDE00", "_")
        val marks =
            listOf(
                EditorCommands.BOLD,
                EditorCommands.ITALIC,
                EditorCommands.HIGHLIGHT,
                EditorCommands.CODE,
                EditorCommands.STRIKE,
            )
        val random = Random(7)
        repeat(20_000) {
            val text = (0 until random.nextInt(1, 10)).joinToString("") { parts.random(random) }
            val start = random.nextInt(0, text.length)
            val end = random.nextInt(start + 1, text.length + 1)
            for (mark in marks) {
                val once = EditorCommands.wrap(text, start, end, mark)
                val words = { of: String -> of.filter { it != mark[0] } }
                assertThat(words(once.text)).isEqualTo(words(text))
                // Where the first press put marks on, the second takes them off.
                if (once.text.length > text.length && text.substring(start, end).any { it != mark[0] }) {
                    assertThat(EditorCommands.wrap(once.text, once.start, once.end, mark).text).isEqualTo(text)
                }
            }
        }
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
