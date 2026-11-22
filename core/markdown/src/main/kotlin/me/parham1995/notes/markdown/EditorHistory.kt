package me.parham1995.notes.markdown

/**
 * The editor's undo steps. A step is the text and the selection before a
 * change, so undo puts both back.
 *
 * A button is one step. Typing is one step for each run of keys: a run ends
 * when a space or a line break is typed, when the caret is moved, and at a
 * button. A step holds the text it had, which is no copy: the text of a
 * string never changes.
 */
class EditorHistory(
    private val limit: Int = LIMIT,
) {
    private val undone = ArrayDeque<EditorEdit>()
    private val redone = ArrayDeque<EditorEdit>()
    private var typing = false

    val canUndo: Boolean get() = undone.isNotEmpty()
    val canRedo: Boolean get() = redone.isNotEmpty()

    /** Keys changed the text from [before] to [after]. */
    fun typed(
        before: EditorEdit,
        after: EditorEdit,
    ) {
        if (!typing) push(before)
        redone.clear()
        // A word is a step: the next key after a space starts another.
        val grew = after.text.length == before.text.length + 1
        typing = !(grew && after.start in 1..after.text.length && after.text[after.start - 1].isWhitespace())
    }

    /** A button is about to change [before]: one step, whatever it does. */
    fun command(before: EditorEdit) {
        push(before)
        redone.clear()
        typing = false
    }

    /** The caret moved with no change of the text: the next key starts a step. */
    fun moved() {
        typing = false
    }

    /** The text before the last step, or null when there is none. [current] is kept for [redo]. */
    fun undo(current: EditorEdit): EditorEdit? {
        val step = undone.removeLastOrNull() ?: return null
        redone.addLast(current)
        typing = false
        return step
    }

    fun redo(current: EditorEdit): EditorEdit? {
        val step = redone.removeLastOrNull() ?: return null
        undone.addLast(current)
        typing = false
        return step
    }

    private fun push(step: EditorEdit) {
        undone.addLast(step)
        if (undone.size > limit) undone.removeFirst()
    }

    private companion object {
        const val LIMIT = 200
    }
}
