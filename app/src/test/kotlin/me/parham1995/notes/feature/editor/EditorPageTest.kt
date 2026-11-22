package me.parham1995.notes.feature.editor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import me.parham1995.notes.data.ThemeChoice
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.ui.theme.NotesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private fun page() {
        runBlocking { session.open() }
        compose.setContent {
            NotesTheme(ThemeChoice.DARK) {
                EditorPage(
                    session = session,
                    title = "Plan",
                    onDone = { done++ },
                    onReload = { reloads++ },
                )
            }
        }
        compose.waitForIdle()
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
