package me.parham1995.notes.ui.render

import android.widget.Magnifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.markdown.MarkdownParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * A tap on the page is the page's -- it is what hides the bars for full-screen
 * reading -- and a tap on something on it is that thing's alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", shadows = [TapOnPageTest.NoMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TapOnPageTest {
    /** The magnifier a hold raises needs a surface Robolectric does not give it. */
    @Implements(Magnifier::class)
    class NoMagnifier {
        @Implementation
        fun show(
            sourceCenterX: Float,
            sourceCenterY: Float,
            magnifierCenterX: Float,
            magnifierCenterY: Float,
        ) = Unit

        @Implementation
        fun update() = Unit

        @Implementation
        fun dismiss() = Unit
    }

    @get:Rule
    val compose = createComposeRule()

    private var taps = 0
    private val followed = mutableListOf<String>()
    private val ticked = mutableListOf<Int>()

    private fun render(markdown: String) {
        val blocks = MarkdownParser.parseNote(markdown).blocks
        val actions =
            RenderActions(
                inline = InlineActions(onWikiLink = { target, _ -> followed += target }),
                onCompleteTask = { ticked += it },
            )
        compose.setContent { MarkdownDocument(blocks = blocks, actions = actions, onTapPage = { taps++ }) }
    }

    @Test
    fun `a tap on the words is the page's`() {
        render("A paragraph of plain words to tap on.\n")
        compose.onNodeWithText("plain words", substring = true).performClick()
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun `a tap on a link follows it and nothing more`() {
        render("[[Bakery]] is where the bread is.\n")
        compose.onNodeWithText("where the bread", substring = true).performTouchInput {
            click(centerLeft + Offset(12f, 0f))
        }
        assertThat(followed).containsExactly("Bakery")
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun `a tap on a box ticks it and nothing more`() {
        render("- [ ] buy flour\n")
        compose.onNodeWithContentDescription("unchecked").performClick()
        assertThat(ticked).hasSize(1)
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun `a hold or a scroll is not a tap`() {
        render((1..80).joinToString("\n\n") { "Paragraph $it of a long note." })
        compose.onNodeWithText("Paragraph 1 of", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("Paragraph 2 of", substring = true).performTouchInput { swipeUp() }
        assertThat(taps).isEqualTo(0)
    }
}
