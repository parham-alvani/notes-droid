package me.parham1995.notes.markdown

/**
 * The editor's undo steps. A step is the text and the selection before a
 * change, so undo puts both back.
 *
 * A button is one step. Typing is one step for each run of keys: a run ends
 * when a space or a line break is typed, when the caret is moved, at a
 * button, and when the keys turn from writing to deleting or back. A change
 * of more than one character at once (a paste, a cut, a word the keyboard
 * wrote whole) is a step of its own. A step holds the text it had, which is
 * no copy: the text of a string never changes.
 */
class EditorHistory(
    private val limit: Int = LIMIT,
) {
    private val undone = ArrayDeque<EditorEdit>()
    private val redone = ArrayDeque<EditorEdit>()
    private var run = Run.NONE

    /** What the run of keys under way does, when there is one. */
    private enum class Run { NONE, WRITES, DELETES }

    val canUndo: Boolean get() = undone.isNotEmpty()
    val canRedo: Boolean get() = redone.isNotEmpty()

    /** Keys changed the text from [before] to [after]. */
    fun typed(
        before: EditorEdit,
        after: EditorEdit,
    ) {
        val delta = after.text.length - before.text.length
        val kind = if (delta < 0) Run.DELETES else Run.WRITES
        // More than one character at once: a paste, a cut, a word written whole.
        val whole = delta > 1 || delta < -1
        if (run != kind || whole) push(before)
        redone.clear()
        // A word is a step: the next key after a space starts another.
        val spaced = delta == 1 && after.start in 1..after.text.length && after.text[after.start - 1].isWhitespace()
        run = if (whole || spaced) Run.NONE else kind
    }

    /** A button is about to change [before]: one step, whatever it does. */
    fun command(before: EditorEdit) {
        push(before)
        redone.clear()
        run = Run.NONE
    }

    /** The caret moved with no change of the text: the next key starts a step. */
    fun moved() {
        run = Run.NONE
    }

    /** The text before the last step, or null when there is none. [current] is kept for [redo]. */
    fun undo(current: EditorEdit): EditorEdit? {
        val step = undone.removeLastOrNull() ?: return null
        redone.addLast(current)
        run = Run.NONE
        return step
    }

    fun redo(current: EditorEdit): EditorEdit? {
        val step = redone.removeLastOrNull() ?: return null
        undone.addLast(current)
        run = Run.NONE
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
