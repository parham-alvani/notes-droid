package me.parham1995.notes.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.markdown.EditorCommands
import me.parham1995.notes.markdown.NoteChanged
import me.parham1995.notes.markdown.VaultEdits
import org.junit.Test

/**
 * The editor's save, by the rule of `look.md` (notes-linux's `docs/specs/`,
 * "Android: the editor"): one write when the text changed, and nothing
 * written when the file is no longer the text that was opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorSessionTest {
    /** The file, written the way the vault writes: by the edit's own rule. */
    private var file: String? = "a"
    private val written = ArrayList<Pair<String, String>>()
    private var answer: WriteResult? = null

    private fun session() =
        EditorSession(
            read = { file },
            write = { opened, text ->
                written += opened to text
                answer ?: try {
                    VaultEdits.edit(opened, text)(file)?.let { file = it }
                    WriteResult.Pushed
                } catch (changed: NoteChanged) {
                    WriteResult.Refused(changed.message.orEmpty(), changed = true)
                }
            },
        )

    private fun EditorSession.type(text: String) {
        change(TextFieldValue(text, TextRange(text.length)))
    }

    /** `aSaveOnThePhoneRefusesAChangedFile` of the spec. */
    @Test
    fun aSaveOnThePhoneRefusesAChangedFile() =
        runTest {
            // The editor opened `a`, and the file is now `b`: saving `c` writes nothing.
            val editor = session().apply { open() }
            file = "b"
            editor.type("c")

            assertThat(editor.save()).isFalse()
            assertThat(file).isEqualTo("b")
            // The editor stays open with what was typed, and says so.
            assertThat(editor.changed).isTrue()
            assertThat(editor.value.text).isEqualTo("c")

            // With the file still `a`, it becomes `c`.
            file = "a"
            val other = session().apply { open() }
            other.type("c")
            assertThat(other.save()).isTrue()
            assertThat(file).isEqualTo("c")
            assertThat(other.changed).isFalse()
        }

    @Test
    fun `a button is one undo step and keeps the selection, and undo puts the text back`() =
        runTest {
            file = "a word here"
            val editor = session().apply { open() }
            assertThat(editor.canUndo).isFalse()
            editor.change(TextFieldValue(file!!, TextRange(2, 6)))
            assertThat(editor.selectionInLine).isTrue()

            editor.run(EditorCommands.wrap(editor.value.text, 2, 6, EditorCommands.BOLD))
            assertThat(editor.value.text).isEqualTo("a **word** here")
            assertThat(editor.value.selection).isEqualTo(TextRange(4, 8))
            assertThat(editor.dirty).isTrue()
            assertThat(editor.canUndo).isTrue()

            editor.undo()
            assertThat(editor.value.text).isEqualTo("a word here")
            assertThat(editor.value.selection).isEqualTo(TextRange(2, 6))
            assertThat(editor.canUndo).isFalse()
            assertThat(editor.dirty).isFalse()
            editor.redo()
            assertThat(editor.value.text).isEqualTo("a **word** here")
            assertThat(editor.canRedo).isFalse()
            // A button with nothing to do is no step.
            editor.undo()
            editor.run(null)
            assertThat(editor.canUndo).isFalse()
        }

    @Test
    fun `the format bar is for a selection inside one line`() =
        runTest {
            file = "one\ntwo"
            val editor = session().apply { open() }
            assertThat(editor.selectionInLine).isFalse()
            editor.change(TextFieldValue(file!!, TextRange(0, 3)))
            assertThat(editor.selectionInLine).isTrue()
            editor.change(TextFieldValue(file!!, TextRange(4, 7)))
            assertThat(editor.selectionInLine).isTrue()
            // Over the line break it is two paragraphs.
            editor.change(TextFieldValue(file!!, TextRange(2, 5)))
            assertThat(editor.selectionInLine).isFalse()
            editor.change(TextFieldValue(file!!, TextRange(5, 2)))
            assertThat(editor.selectionInLine).isFalse()
        }

    @Test
    fun `typing is undone a word at a time`() =
        runTest {
            val editor = session().apply { open() }
            for (text in listOf("ab", "ab ", "ab c")) editor.type(text)
            editor.undo()
            assertThat(editor.value.text).isEqualTo("ab ")
            editor.undo()
            assertThat(editor.value.text).isEqualTo("a")
        }

    @Test
    fun `a text that did not change is not written`() =
        runTest {
            val editor = session().apply { open() }
            assertThat(editor.dirty).isFalse()
            assertThat(editor.save()).isTrue()
            assertThat(written).isEmpty()
        }

    @Test
    fun `a save is one write of the text, against the text that was opened`() =
        runTest {
            val editor = session().apply { open() }
            editor.type("a\nmore")
            assertThat(editor.dirty).isTrue()

            assertThat(editor.save()).isTrue()
            assertThat(written).containsExactly("a" to "a\nmore")
        }

    @Test
    fun `reload takes the file's text and drops what was typed`() =
        runTest {
            val editor = session().apply { open() }
            file = "b"
            editor.type("c")
            editor.save()

            editor.reload()

            assertThat(editor.value.text).isEqualTo("b")
            assertThat(editor.changed).isFalse()
            assertThat(editor.dirty).isFalse()
            // What is typed from here is saved against the text that was read again.
            editor.type("b2")
            assertThat(editor.save()).isTrue()
            assertThat(file).isEqualTo("b2")
        }

    @Test
    fun `a save that waits in the queue closes the editor, and another refusal keeps it open`() =
        runTest {
            val editor = session().apply { open() }
            editor.type("c")
            answer = WriteResult.Queued("no signal")
            assertThat(editor.save()).isTrue()
            assertThat(editor.message).isNotNull()

            editor.message = null
            answer = WriteResult.Refused("set a name and email to commit as, in Settings")
            assertThat(editor.save()).isFalse()
            assertThat(editor.changed).isFalse()
            // It says why, and stays open with the text: the page offers Copy My Text and a way out.
            assertThat(editor.refused).isNotNull()
            assertThat(editor.value.text).isEqualTo("c")
            // The next save that lands takes the word away.
            answer = null
            assertThat(editor.save()).isTrue()
            assertThat(editor.refused).isNull()
        }

    @Test
    fun `no key changes the text while a save is on its way`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val editor =
                EditorSession(
                    read = { file },
                    write = { _, text ->
                        gate.await()
                        file = text
                        WriteResult.Pushed
                    },
                )
            editor.open()
            editor.type("ab")
            val save = async { editor.save() }
            runCurrent()
            assertThat(editor.saving).isTrue()

            // A key, a button and an undo that come now would be lost when the editor closes.
            editor.type("abc")
            editor.run(EditorCommands.wrap(editor.value.text, 0, 2, EditorCommands.BOLD))
            editor.undo()
            assertThat(editor.value.text).isEqualTo("ab")

            gate.complete(Unit)
            assertThat(save.await()).isTrue()
            assertThat(file).isEqualTo("ab")
            assertThat(editor.saving).isFalse()
        }

    @Test
    fun `a write that fails leaves the editor open and not saving`() =
        runTest {
            val editor = EditorSession(read = { file }, write = { _, _ -> error("no disk") })
            editor.open()
            editor.type("ab")
            assertThat(runCatching { editor.save() }.isFailure).isTrue()
            assertThat(editor.saving).isFalse()
            editor.type("abc")
            assertThat(editor.value.text).isEqualTo("abc")
        }

    @Test
    fun `what was typed is kept for when the system ends the app, with the text it was opened from`() =
        runTest {
            file = "# Plan\n"
            val editor = session().apply { open() }
            // Nothing typed: nothing to keep.
            assertThat(editor.draft()).isNull()
            editor.change(TextFieldValue("# Plan\nmore", TextRange(9, 11)))
            val draft = editor.draft()!!
            assertThat(draft).isEqualTo(EditorDraft("# Plan\n", "# Plan\nmore", 9, 11))

            // The app comes back: the editor opens with the text and the selection, and it is still to save.
            val back = session().apply { restore(draft) }
            assertThat(back.value.text).isEqualTo("# Plan\nmore")
            assertThat(back.value.selection).isEqualTo(TextRange(9, 11))
            assertThat(back.dirty).isTrue()
            assertThat(back.save()).isTrue()
            assertThat(file).isEqualTo("# Plan\nmore")
        }

    @Test
    fun `a kept text is still refused when the file changed while the app was away`() =
        runTest {
            val editor = session().apply { open() }
            editor.type("c")
            val draft = editor.draft()!!
            file = "b"

            val back = session().apply { restore(draft) }
            assertThat(back.save()).isFalse()
            assertThat(back.changed).isTrue()
            assertThat(file).isEqualTo("b")
            assertThat(back.value.text).isEqualTo("c")
        }

    @Test
    fun `a note too long for the saved state is not kept there`() =
        runTest {
            file = "a".repeat(EditorSession.DRAFT_LIMIT / 2)
            val editor = session().apply { open() }
            editor.type(file + "b")
            assertThat(editor.draft()).isNull()
        }

    @Test
    fun `the editor opens at the heading being read under`() =
        runTest {
            file = "# A\n\ntext\n\n## B\n\nmore"
            val editor = session().apply { open(heading = 1) }
            assertThat(editor.value.selection).isEqualTo(TextRange(file!!.indexOf("## B") + 4))

            val top = session().apply { open() }
            assertThat(top.value.selection).isEqualTo(TextRange(0))
            // A note with fewer headings than that opens at its start.
            val past = session().apply { open(heading = 9) }
            assertThat(past.value.selection).isEqualTo(TextRange(0))
        }

    @Test
    fun `the caret's heading is said by its number, for the reader to go back to`() =
        runTest {
            file = "intro\n# A\n\ntext\n\n## B\n\nmore"
            val editor = session()
            assertThat(editor.heading()).isNull()
            editor.open()
            assertThat(editor.heading()).isNull()
            editor.change(TextFieldValue(file!!, TextRange(file!!.indexOf("text"))))
            assertThat(editor.heading()).isEqualTo(0)
            // The same number the editor opens at.
            val under = session().apply { open(heading = 1) }
            assertThat(under.heading()).isEqualTo(1)
        }

    @Test
    fun `a note that is not on the device says so`() =
        runTest {
            file = null
            val editor = session().apply { open() }
            assertThat(editor.missing).isTrue()
            assertThat(editor.save()).isTrue()
            assertThat(written).isEmpty()
        }
}
