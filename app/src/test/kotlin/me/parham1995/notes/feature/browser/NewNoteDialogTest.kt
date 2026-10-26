package me.parham1995.notes.feature.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.TemplateFile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The new-note form hands back the template chosen and the title typed, and
 * Done on the keyboard asks for the note.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NewNoteDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val templates =
        listOf(TemplateFile("Templates/Meeting.md", "Meeting"), TemplateFile("Templates/Trip.md", "Trip"))

    @Test
    fun `a title typed and a template picked are handed back, and done asks for the note`() {
        var typed = ""
        var picked: TemplateFile? = templates.first()
        var done = 0
        compose.setContent {
            var title by remember { mutableStateOf("") }
            var chosen by remember { mutableStateOf(templates.first()) }
            NewNoteForm(
                folder = "Work",
                templates = templates,
                title = title,
                onTitle = {
                    title = it
                    typed = it
                },
                chosen = chosen,
                onChoose = {
                    chosen = it
                    picked = it
                },
                onDone = { done++ },
            )
        }

        compose.onNodeWithText("In Work").assertExists()
        compose.onNodeWithText("Trip").performClick()
        compose.onNodeWithText("Title").performTextInput("Lisbon")
        compose.onNodeWithText("Lisbon").performImeAction()

        assertThat(typed).isEqualTo("Lisbon")
        assertThat(picked?.path).isEqualTo("Templates/Trip.md")
        assertThat(done).isEqualTo(1)
    }

    @Test
    fun `one template is not a choice`() {
        compose.setContent {
            NewNoteForm("", templates.take(1), "", {}, templates.first(), {}, {})
        }
        compose.onNodeWithText("In Vault").assertExists()
        compose.onNodeWithText("Meeting").assertDoesNotExist()
    }
}
