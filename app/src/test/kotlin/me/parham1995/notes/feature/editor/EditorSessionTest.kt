package me.parham1995.notes.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.markdown.NoteChanged
import me.parham1995.notes.markdown.VaultEdits
import org.junit.Test

/**
 * The editor's save, by the rule of `look.md` (notes-linux's `docs/specs/`,
 * "Android: the editor"): one write when the text changed, and nothing
 * written when the file is no longer the text that was opened.
 */
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
        value = TextFieldValue(text, TextRange(text.length))
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
            assertThat(editor.message).isNotNull()
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
    fun `a note that is not on the device says so`() =
        runTest {
            file = null
            val editor = session().apply { open() }
            assertThat(editor.missing).isTrue()
            assertThat(editor.save()).isTrue()
            assertThat(written).isEmpty()
        }
}
