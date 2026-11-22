package me.parham1995.notes.feature.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.theme.NotesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The editor's page reaches its handlers: Done saves, and a file that changed
 * meanwhile is said, with Reload and Copy My Text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class EditorPageTest {
    @get:Rule
    val compose = createComposeRule()

    private var file = "# Plan\n"
    private var answer: WriteResult = WriteResult.Pushed
    private var done = 0
    private var reloads = 0

    private val session =
        EditorSession(
            read = { file },
            write = { _, text ->
                if (answer == WriteResult.Pushed) file = text
                answer
            },
        )

    private fun page(reading: ReadingSettings = ReadingSettings()) {
        runBlocking { session.open() }
        compose.setContent {
            NotesTheme(ThemeChoice.DARK) {
                CompositionLocalProvider(LocalReading provides reading) {
                    EditorPage(
                        session = session,
                        title = "Plan",
                        onDone = { done++ },
                        onReload = { reloads++ },
                        today = LocalDate.of(2026, 11, 22),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The middle of line [line] of the editor's text, at [x] from its start. */
    private fun at(
        line: Int,
        x: Float = 8f,
    ): Offset {
        val results = ArrayList<TextLayoutResult>()
        compose.onNodeWithTag(EDITOR_TAG).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        return Offset(x, (layout.getLineTop(line) + layout.getLineBottom(line)) / 2)
    }

    @Test
    fun `the format bar is there for a selection in one line, and a button wraps it and keeps it`() {
        file = "# Plan\nmore\n"
        page()
        compose.onNodeWithTag(FORMAT_BAR_TAG).assertDoesNotExist()

        compose.onNodeWithTag(EDITOR_TAG).performTextInputSelection(TextRange(2, 6))
        compose.onNodeWithTag(FORMAT_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Bold").performClick()
        assertThat(session.value.text).isEqualTo("# **Plan**\nmore\n")
        assertThat(session.value.selection).isEqualTo(TextRange(4, 8))
        // The selection is kept, so the bar is still there, and a second press takes the marks off.
        compose.onNodeWithTag(FORMAT_BAR_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Bold").performClick()
        assertThat(session.value.text).isEqualTo(file)

        for ((button, text) in listOf(
            "Italic" to "# *Plan*\nmore\n",
            "Highlight" to "# ==Plan==\nmore\n",
            "Code" to "# `Plan`\nmore\n",
            "Strikethrough" to "# ~~Plan~~\nmore\n",
            "Link to a note" to "# [[Plan]]\nmore\n",
        )) {
            compose.onNodeWithContentDescription(button).performClick()
            assertThat(session.value.text).isEqualTo(text)
            // One undo step each.
            compose.onNodeWithContentDescription("Undo").performClick()
            assertThat(session.value.text).isEqualTo(file)
            assertThat(session.value.selection).isEqualTo(TextRange(2, 6))
        }

        // Over two lines there is no bar.
        compose.onNodeWithTag(EDITOR_TAG).performTextInputSelection(TextRange(2, 9))
        compose.onNodeWithTag(FORMAT_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun `the format bar is off with the setting off`() {
        page(ReadingSettings(formatBar = false))
        compose.onNodeWithTag(EDITOR_TAG).performTextInputSelection(TextRange(2, 6))
        compose.onNodeWithTag(FORMAT_BAR_TAG).assertDoesNotExist()
    }

    @Test
    fun `a button of the format bar is 48 dp`() {
        page()
        compose.onNodeWithTag(EDITOR_TAG).performTextInputSelection(TextRange(2, 6))
        val bounds = compose.onNodeWithContentDescription("Bold").getUnclippedBoundsInRoot()
        assertThat((bounds.right - bounds.left).value).isWithin(0.5f).of(48f)
        assertThat((bounds.bottom - bounds.top).value).isWithin(0.5f).of(48f)
    }

    @Test
    fun `a tap on a task's box changes its status in the text, as one undo step`() {
        file = "# Plan\n- [ ] Call\n"
        page()
        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { click(at(1)) }
        compose.waitForIdle()
        assertThat(session.value.text).isEqualTo("# Plan\n- [x] Call ✅ 2026-11-22\n")
        // The caret is where it was, and nothing is written until the editor saves.
        assertThat(session.value.selection).isEqualTo(TextRange(0))
        assertThat(session.dirty).isTrue()
        assertThat(file).isEqualTo("# Plan\n- [ ] Call\n")

        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { click(at(1)) }
        compose.waitForIdle()
        assertThat(session.value.text).isEqualTo(file)
        compose.onNodeWithContentDescription("Undo").performClick()
        assertThat(session.value.text).isEqualTo("# Plan\n- [x] Call ✅ 2026-11-22\n")
    }

    @Test
    fun `a long press on a task's box opens the statuses, and one of them is set`() {
        file = "# Plan\n- [ ] Call\n"
        page()
        compose.onNodeWithTag(EDITOR_TAG).performTouchInput { longClick(at(1)) }
        compose.waitForIdle()
        compose.onNodeWithText("In Progress").assertIsDisplayed()
        compose.onNodeWithText("Cancelled").performClick()
        compose.waitForIdle()
        assertThat(session.value.text).isEqualTo("# Plan\n- [-] Call ❌ 2026-11-22\n")
        compose.onNodeWithText("In Progress").assertDoesNotExist()
    }

    @Test
    fun `the note's text is in the field, and typing changes the session's text`() {
        page()
        compose.onNodeWithTag(EDITOR_TAG).performTextInput("x")
        assertThat(session.value.text).contains("x")
        assertThat(session.dirty).isTrue()
    }

    @Test
    fun `done reaches its handler`() {
        page()
        compose.onNodeWithText("Done").performClick()
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun `a file that changed is said, with reload behind a confirm and copy my text`() {
        page()
        answer = WriteResult.Refused("changed", changed = true)
        compose.onNodeWithTag(EDITOR_TAG).performTextInput("typed ")
        runBlocking { session.save() }
        compose.waitForIdle()

        compose.onNodeWithTag(EDITOR_CHANGED_TAG).assertIsDisplayed()
        compose.onNodeWithText("Copy My Text").assertIsDisplayed()
        // Reload asks first: what was typed would be dropped.
        compose.onNodeWithText("Reload").performClick()
        assertThat(reloads).isEqualTo(0)
        compose.onNodeWithText("Reload the note?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(reloads).isEqualTo(0)
        assertThat(session.value.text).contains("typed")
    }
}
