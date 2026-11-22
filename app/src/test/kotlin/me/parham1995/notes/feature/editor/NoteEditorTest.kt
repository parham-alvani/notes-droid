package me.parham1995.notes.feature.editor

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
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
import java.time.LocalDate

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

    /** The places a tap and a long press on a task's box named. */
    private val tapped = ArrayList<Int>()
    private val held = ArrayList<Int>()

    private fun edit(
        text: String,
        caret: Int = text.length,
    ) {
        value = TextFieldValue(text, TextRange(caret))
        compose.setContent {
            NotesTheme(ThemeChoice.DARK) {
                NoteEditor(
                    value,
                    { value = it },
                    Modifier.width(400.dp),
                    today = LocalDate.of(2026, 11, 22),
                    onTask = { tapped += it },
                    onTaskMenu = { held += it },
                )
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
    fun `a task's marks are as wide as its box, and its line is as high as any other`() {
        edit("plain\n- [ ] Buy\n1. [x] done\n\nlast")
        val layout = layout()
        val em = 15f
        val start = layout.getLineStart(1)
        // `- [ ]` is five characters and takes 1.1 em: the space after it starts there.
        val room = layout.getHorizontalPosition(start + 5, true) - layout.getHorizontalPosition(start, true)
        assertThat(room).isWithin(1f).of(1.1f * em)
        // After a number the brackets alone are the box.
        val numbered = layout.getLineStart(2)
        val brackets =
            layout.getHorizontalPosition(numbered + 6, true) - layout.getHorizontalPosition(numbered + 3, true)
        assertThat(brackets).isWithin(1f).of(1.1f * em)

        fun height(line: Int) = layout.getLineBottom(line) - layout.getLineTop(line)
        assertThat(height(1)).isEqualTo(height(0))
        // The letters that keep the room do not move the words off the line's baseline.
        assertThat(layout.getLineBaseline(1) - layout.getLineTop(1)).isWithin(0.5f).of(layout.getLineBaseline(0))
        assertThat(value.text).isEqualTo("plain\n- [ ] Buy\n1. [x] done\n\nlast")
    }

    @Test
    fun `a tap on a task's box names its line and does not move the caret`() {
        val text = "plain\n- [ ] Buy\n- [ ] Sell\n\nlast"
        edit(text)
        val layout = layout()

        fun middle(line: Int) = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { click(Offset(8f, middle(1))) }
        compose.waitForIdle()
        assertThat(tapped).containsExactly(text.indexOf("- [ ] Buy"))
        assertThat(value.selection).isEqualTo(TextRange(text.length))

        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { click(Offset(8f, middle(2))) }
        compose.waitForIdle()
        assertThat(tapped).containsExactly(text.indexOf("- [ ] Buy"), text.indexOf("- [ ] Sell")).inOrder()
        assertThat(held).isEmpty()

        // In the words, well past the box's target, a tap is the text field's.
        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { click(Offset(120f, middle(1))) }
        compose.waitForIdle()
        assertThat(tapped).hasSize(2)
        // A line with no box has nothing to tap.
        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { click(Offset(8f, middle(0))) }
        compose.waitForIdle()
        assertThat(tapped).hasSize(2)
        assertThat(value.text).isEqualTo(text)
    }

    @Test
    fun `the box's target is 40 dp, and a long press on it opens the status sheet`() {
        val text = "- [ ] Buy\n\nlast"
        edit(text)
        val layout = layout()
        val middle = (layout.getLineTop(0) + layout.getLineBottom(0)) / 2
        // The box is 16 dp wide from the line's start: 26 dp is past it and inside its target.
        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { click(Offset(26f, middle)) }
        compose.waitForIdle()
        assertThat(tapped).containsExactly(0)
        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { click(Offset(40f, middle)) }
        compose.waitForIdle()
        assertThat(tapped).hasSize(1)

        // That tap was the text's: the caret went there. A long press on the box leaves it.
        val caret = value.selection
        assertThat(caret).isNotEqualTo(TextRange(text.length))
        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { longClick(Offset(8f, middle)) }
        compose.waitForIdle()
        assertThat(held).containsExactly(0)
        assertThat(tapped).hasSize(1)
        assertThat(value.selection).isEqualTo(caret)
    }

    @Test
    fun `with the caret in a task's marks there is no box to tap`() {
        edit("- [ ] Buy\n\nlast", caret = 2)
        val layout = layout()
        compose.onNodeWithTag(EDITOR_TAG).performTouchInput {
            click(Offset(8f, (layout.getLineTop(0) + layout.getLineBottom(0)) / 2))
        }
        compose.waitForIdle()
        assertThat(tapped).isEmpty()
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
