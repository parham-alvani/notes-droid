package me.parham1995.notes.feature.note

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.FrontMatterForm
import me.parham1995.notes.markdown.PropertyEdit
import me.parham1995.notes.markdown.PropertySheet
import me.parham1995.notes.markdown.PropertyType
import me.parham1995.notes.markdown.PropertyValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A row of the Properties form hands back the edit its control makes: the
 * kind of thing that compiles, draws, and does nothing when it is wired to
 * nothing. Synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PropertyRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun property(note: String) = (PropertySheet.read(note) as FrontMatterForm.Form).sheet.properties.single()

    private fun render(
        note: String,
        type: PropertyType,
    ): MutableList<PropertyEdit> {
        val edits = mutableListOf<PropertyEdit>()
        val property = property(note)
        compose.setContent { PropertyRow(property, type, enabled = true, onEdit = { edits += it }) }
        return edits
    }

    @Test
    fun `ticking a checkbox sets it true`() {
        val edits = render("---\ndone: false\n---\n", PropertyType.CHECKBOX)

        compose.onNodeWithText("done").assertExists()
        compose.onNode(isToggleable()).performClick()

        assertThat(edits).containsExactly(PropertyEdit.Set("done", PropertyValue.Text("true")))
    }

    @Test
    fun `a list item comes off by its chip, and one is added from the field`() {
        val edits = render("---\ntags: [a, b]\n---\n", PropertyType.TAGS)

        compose.onNodeWithContentDescription("Remove a", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Add an item").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("c")
        compose.onNode(hasSetTextAction()).performImeAction()

        assertThat(edits)
            .containsExactly(
                PropertyEdit.Set("tags", PropertyValue.Items(listOf("b"))),
                PropertyEdit.Set("tags", PropertyValue.Items(listOf("a", "b", "c"))),
            ).inOrder()
    }

    @Test
    fun `text is written on Done, and only when it changed`() {
        val edits = render("---\ntitle: Plan\n---\n", PropertyType.TEXT)

        compose.onNodeWithText("Plan").performImeAction()
        compose.onNodeWithText("Plan").performTextReplacement("Plans")
        compose.onNodeWithText("Plans").performImeAction()

        assertThat(edits).containsExactly(PropertyEdit.Set("title", PropertyValue.Text("Plans")))
    }

    @Test
    fun `the menu removes the property`() {
        val edits = render("---\ntitle: Plan\n---\n", PropertyType.TEXT)

        compose.onNodeWithContentDescription("More for title").performClick()
        compose.onNodeWithText("Remove").performClick()

        assertThat(edits).containsExactly(PropertyEdit.Remove("title"))
    }
}
