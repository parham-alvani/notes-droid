package me.parham1995.notes.ui.render

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.ui.LocalReading
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * That the reader's text size reaches everything that is part of the text.
 *
 * Prose grew and everything set beside it stayed put: a bullet the size of a
 * full stop next to a line twice as tall, a checkbox smaller than the letters it
 * ticks, a date chip still in the smallest type there is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextScaleTest {
    @get:Rule
    val compose = createComposeRule()

    private var scale by mutableStateOf(1f)

    private fun render(markdown: String) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        compose.setContent {
            CompositionLocalProvider(LocalReading provides ReadingSettings(textScale = scale)) {
                blocks.forEach { MdBlockView(it, RenderActions(), emptySet()) }
            }
        }
    }

    /** How much taller [measure] got when the reading size doubled. */
    private fun growth(measure: () -> Float): Float {
        scale = 1f
        compose.waitForIdle()
        val before = measure()
        scale = 2f
        compose.waitForIdle()
        return measure() / before
    }

    @Test
    fun `a bullet grows with the text`() {
        render("- an item\n")
        val growth =
            growth {
                compose
                    .onNodeWithText("•")
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            }
        assertThat(growth).isGreaterThan(1.5f)
    }

    @Test
    fun `a task's box grows with the text`() {
        render("- [ ] a task\n")
        val growth =
            growth {
                compose
                    .onNodeWithContentDescription("unchecked", useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            }
        assertThat(growth).isGreaterThan(1.5f)
    }

    @Test
    fun `a date chip grows with the text`() {
        render("- [ ] a task 📅 2026-09-20\n")
        val growth =
            growth {
                compose
                    .onNodeWithText("2026-09-20")
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            }
        assertThat(growth).isGreaterThan(1.5f)
    }

    @Test
    fun `an attachment's name grows with the text`() {
        render("![[uploads/contract.pdf]]")
        val growth =
            growth {
                compose
                    .onNodeWithText("contract.pdf")
                    .fetchSemanticsNode()
                    .boundsInRoot.height
            }
        assertThat(growth).isGreaterThan(1.5f)
    }
}
