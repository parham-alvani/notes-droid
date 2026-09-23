package me.parham1995.notes.ui.render

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the renderer says to a screen reader, and how big its targets are.
 *
 * A task's box was an icon with a click handler -- to TalkBack an image with a
 * name, not a checkbox with a state. A heading was text in a bigger font. None
 * of it fails to compile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class RenderAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        markdown: String,
        actions: RenderActions = RenderActions(),
    ) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        compose.setContent { blocks.forEach { MdBlockView(it, actions, emptySet()) } }
    }

    @Test
    fun `a task's box is a checkbox, with its state`() {
        render("- [ ] open\n- [x] done\n", RenderActions(onCompleteTask = {}))

        compose
            .onNodeWithContentDescription("unchecked")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        compose
            .onNodeWithContentDescription("checked")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun `a heading is a heading to a screen reader`() {
        render("# The title\n\nbody")

        compose.onNode(isHeading() and hasText("The title")).assertExists()
        compose.onNode(isHeading() and hasText("body")).assertDoesNotExist()
    }

    @Test
    fun `a foldable callout's header is a button that says whether it is open`() {
        render("> [!tip]+ Fold me\n> the body")

        val header = compose.onNode(hasText("Fold me", substring = true) and hasRole(Role.Button))
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        header.performClick()
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test
    fun `a code block with no language can still be copied`() {
        // The header on its own: the block around it starts the highlighter,
        // whose library is built for a newer JVM than the tests run on.
        val copied = mutableListOf<String>()
        compose.setContent { CodeBlockHeader(language = null, known = false, onCopy = { copied += "plain text" }) }

        compose.onNodeWithContentDescription("Copy").performClick()
        assertThat(copied).containsExactly("plain text")
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
}
