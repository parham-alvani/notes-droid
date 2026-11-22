package me.parham1995.notes.feature.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.feature.tasks.describe
import me.parham1995.notes.markdown.EditorDocument
import me.parham1995.notes.markdown.EditorEdit
import me.parham1995.notes.markdown.EditorHistory
import me.parham1995.notes.ui.UiText

/**
 * One note open in the editor, from the text it opened to its save
 * (`look.md` in notes-linux's `docs/specs/`, "Android: the editor").
 *
 * The save is "this text, when the file is still the text the editor
 * opened". When the file changed meanwhile, nothing is written, the editor
 * stays open with what was typed, and [changed] offers two ways on: read the
 * file again ([reload]) or copy the text.
 *
 * [read] reads the file and [write] is the vault's ordinary write, so the
 * rule can be tested with neither a vault nor a screen.
 */
internal class EditorSession(
    private val read: suspend () -> String?,
    private val write: suspend (opened: String, text: String) -> WriteResult,
) {
    /** The file's text when it was opened, or read again. Null until it is read. */
    var opened: String? by mutableStateOf(null)
        private set

    /** The text being edited, and the caret. It is the file's Markdown and nothing else. */
    var value by mutableStateOf(TextFieldValue(""))
        private set

    private var history = EditorHistory()

    /** Whether there is a step to undo, and one to do again. */
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val TextFieldValue.step get() = EditorEdit(text, selection.start, selection.end)

    /** The text field's change: keys, or a move of the caret. Keys are an undo step a word. */
    fun change(next: TextFieldValue) {
        val before = value
        if (next.text != before.text) {
            history.typed(before.step, next.step)
            steps()
        } else if (next.selection != before.selection) {
            history.moved()
        }
        value = next
    }

    /**
     * A button's change of the text: one undo step. The selection is the
     * edit's, so a format button keeps the words it was on. A null [edit] is
     * a button that had nothing to do.
     */
    fun run(edit: EditorEdit?) {
        if (edit == null || opened == null) return
        history.command(value.step)
        value = TextFieldValue(edit.text, TextRange(edit.start, edit.end))
        steps()
    }

    fun undo() {
        history.undo(value.step)?.let { value = TextFieldValue(it.text, TextRange(it.start, it.end)) }
        steps()
    }

    fun redo() {
        history.redo(value.step)?.let { value = TextFieldValue(it.text, TextRange(it.start, it.end)) }
        steps()
    }

    private fun steps() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    /** The selection is in one line and is not empty: what the format bar is shown for. */
    val selectionInLine: Boolean
        get() {
            val selection = value.selection
            if (selection.collapsed) return false
            val text = value.text
            // To the first line break from the selection's start: a line's length, not the note's.
            val line = text.indexOf('\n', selection.min)
            return line < 0 || line >= selection.max
        }

    var saving by mutableStateOf(false)
        private set

    /** The file is no longer the text that was opened: the save wrote nothing. */
    var changed by mutableStateOf(false)
        private set

    /** The file is not there to edit. */
    var missing by mutableStateOf(false)
        private set

    var message: UiText? by mutableStateOf(null)

    val dirty: Boolean get() = opened != null && value.text != opened

    /** Reads the file and puts the caret after the heading that is number [heading] of the note, or at the start. */
    suspend fun open(heading: Int? = null) {
        val text = read()
        if (text == null) {
            missing = true
            return
        }
        val caret = heading?.let { EditorDocument().apply { update(text) }.headingEnd(it) } ?: 0
        opened = text
        value = TextFieldValue(text, TextRange(caret))
    }

    /**
     * Saves when the text changed. True when the editor can close: the text
     * is written or waits in the queue, or there was nothing to write.
     */
    suspend fun save(): Boolean {
        val from = opened ?: return true
        val text = value.text
        if (text == from) return true
        if (saving) return false
        saving = true
        val result = write(from, text)
        saving = false
        return when (result) {
            WriteResult.Pushed, WriteResult.Unchanged -> true
            is WriteResult.Queued -> {
                message = result.describe()
                true
            }
            is WriteResult.Refused -> {
                changed = result.changed
                if (!result.changed) message = result.describe()
                false
            }
        }
    }

    /** Takes the file's text as it is now. What was typed is dropped: ask first. */
    suspend fun reload() {
        val text = read()
        if (text == null) {
            missing = true
            return
        }
        opened = text
        value = TextFieldValue(text, TextRange(minOf(value.selection.start, text.length)))
        // What was typed is dropped, and its steps with it.
        history = EditorHistory()
        steps()
        changed = false
    }
}
