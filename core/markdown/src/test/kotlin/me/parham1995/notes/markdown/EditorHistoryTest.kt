package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The editor's undo steps: a button is one step, and typing is a step a word. */
class EditorHistoryTest {
    private val history = EditorHistory()
    private var now = EditorEdit("", 0, 0)

    private fun type(text: String) {
        for (c in text) {
            val next =
                EditorEdit(
                    now.text.substring(0, now.start) + c + now.text.substring(now.end),
                    now.start + 1,
                    now.start + 1,
                )
            history.typed(now, next)
            now = next
        }
    }

    private fun command(edit: EditorEdit) {
        history.command(now)
        now = edit
    }

    private fun undo(): Boolean = history.undo(now)?.also { now = it } != null

    private fun redo(): Boolean = history.redo(now)?.also { now = it } != null

    @Test
    fun `a button is one undo step, and undo puts the selection back`() {
        type("word")
        now = EditorEdit(now.text, 0, 4)
        history.moved()
        command(EditorCommands.wrap(now.text, 0, 4, EditorCommands.BOLD))
        assertThat(now.text).isEqualTo("**word**")

        assertThat(undo()).isTrue()
        assertThat(now).isEqualTo(EditorEdit("word", 0, 4))
        assertThat(redo()).isTrue()
        assertThat(now).isEqualTo(EditorEdit("**word**", 2, 6))
    }

    @Test
    fun `typing is a step a word`() {
        type("one two")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEqualTo("one ")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEmpty()
        assertThat(undo()).isFalse()
        assertThat(history.canRedo).isTrue()
    }

    @Test
    fun `a move of the caret ends a run of keys, and a new key drops what was undone`() {
        type("ab")
        history.moved()
        type("c")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEqualTo("ab")
        type("d")
        assertThat(history.canRedo).isFalse()
        assertThat(now.text).isEqualTo("abd")
    }

    /** The keyboard writes [text] at the caret in one change: a swiped word, a paste. */
    private fun insert(text: String) {
        val next =
            EditorEdit(
                now.text.substring(0, now.start) + text + now.text.substring(now.end),
                now.start + text.length,
                now.start + text.length,
            )
        history.typed(now, next)
        now = next
    }

    private fun backspace(times: Int = 1) {
        repeat(times) {
            val next = EditorEdit(now.text.removeRange(now.start - 1, now.start), now.start - 1, now.start - 1)
            history.typed(now, next)
            now = next
        }
    }

    @Test
    fun `a word the keyboard writes whole is a step of its own`() {
        // Swiped words come with their space in front, so no key ends in a space.
        insert("one")
        insert(" two")
        insert(" three")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEqualTo("one two")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEqualTo("one")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEmpty()
    }

    @Test
    fun `deleting after writing is another step, and undo gives the deleted text back`() {
        type("one two")
        backspace(3)
        assertThat(now.text).isEqualTo("one ")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEqualTo("one two")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEqualTo("one ")
        // Writing after deleting is another step too.
        backspace(2)
        type("x")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEqualTo("on")
        assertThat(undo()).isTrue()
        assertThat(now.text).isEqualTo("one ")
    }

    @Test
    fun `only the last steps are kept`() {
        val short = EditorHistory(limit = 2)
        repeat(5) { short.command(EditorEdit("$it", 0, 0)) }
        assertThat(short.undo(EditorEdit("now", 0, 0))!!.text).isEqualTo("4")
        assertThat(short.undo(EditorEdit("4", 0, 0))!!.text).isEqualTo("3")
        assertThat(short.undo(EditorEdit("3", 0, 0))).isNull()
    }
}
