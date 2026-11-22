package me.parham1995.notes.feature.editor

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.ui.theme.NotesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor in a real text field: what is typed is the note's text and
 * nothing else, and what is drawn takes the room the look says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private var value by mutableStateOf(TextFieldValue(""))

    private fun edit(
        text: String,
        caret: Int = text.length,
    ) {
        value = TextFieldValue(text, TextRange(caret))
        compose.setContent {
            NotesTheme(ThemeChoice.DARK) {
                NoteEditor(value, { value = it }, Modifier.width(400.dp))
            }
        }
        compose.waitForIdle()
    }

    private fun layout(): TextLayoutResult {
        compose.waitForIdle()
        val results = ArrayList<TextLayoutResult>()
        compose.onNodeWithTag(EDITOR_TAG).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test
    fun `what is typed is the note's text, and nothing drawn is in it`() {
        edit("# Title\n\n> [!tip]\n")
        compose.onNodeWithTag(EDITOR_TAG).performTextInput("**b**")
        assertThat(value.text).isEqualTo("# Title\n\n> [!tip]\n**b**")
        assertThat(value.selection).isEqualTo(TextRange(value.text.length))
    }

    @Test
    fun `every line of the note is one line of the field`() {
        // Four lines, the last one empty: no line is added for a paragraph's end.
        edit("one\n\nthree\n")
        val layout = layout()
        assertThat(layout.lineCount).isEqualTo(4)
        // The caret after the last line break is on the last line, at its start.
        assertThat(layout.getLineForOffset(value.text.length)).isEqualTo(3)
        assertThat(layout.getCursorRect(value.text.length).top).isEqualTo(layout.getLineTop(3))
    }

    @Test
    fun `a hidden mark takes no room, and takes it again with the caret in its element`() {
        val text = "a **bold** word\n\nlast"
        edit(text)
        val away = layout()
        // The two stars before "bold" start and end at one place.
        assertThat(away.getHorizontalPosition(4, true)).isEqualTo(away.getHorizontalPosition(2, true))

        compose.onNodeWithTag(EDITOR_TAG).performTextInputSelection(TextRange(5))
        val inside = layout()
        assertThat(inside.getHorizontalPosition(4, true)).isGreaterThan(inside.getHorizontalPosition(2, true))
        assertThat(value.text).isEqualTo(text)
    }

    @Test
    fun `a heading's line and a right-to-left line are as high as the look says`() {
        edit("plain\n# Title\nیادداشت\nlast")
        val layout = layout()

        fun height(line: Int) = layout.getLineBottom(line) - layout.getLineTop(line)
        val plain = height(0)
        // 15 sp at 1.65, at this screen's density of 1.
        assertThat(plain).isWithin(1f).of(15f * 1.65f)
        assertThat(height(1) / plain).isWithin(0.05f).of(1.6f)
        assertThat(height(2) / plain).isWithin(0.05f).of(2f / 1.65f)
        // The last line is a whole line too: nothing is trimmed off it.
        assertThat(height(3)).isEqualTo(plain)
    }

    @Test
    fun `a quote and a callout leave room at the side their lines start`() {
        edit("plain\n> quoted\n\n> [!tip] Title\n> body\nlast")
        val layout = layout()

        fun starts(line: Int) = layout.getHorizontalPosition(layout.getLineStart(line), usePrimaryDirection = true)
        val plain = starts(0)
        val em = 15f
        assertThat(starts(1) - plain).isWithin(1f).of(em)
        // The title's line starts after the icon: 1 em, 1.1 em and 0.4 em.
        assertThat(starts(3) - plain).isWithin(1f).of(2.5f * em)
        assertThat(starts(4) - plain).isWithin(1f).of(em)
    }
}
