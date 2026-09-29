package me.parham1995.notes.ui.render

import android.widget.Magnifier
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Holding a finger on a word in a note looks it up. Rendered inside a
 * selection container as the reader is, because that is what also wants the
 * hold -- and a gesture it swallowed would compile, draw, and do nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp", shadows = [HoldToDefineTest.NoMagnifier::class])
// Real text metrics: by default a whole line measures a few pixels wide, and
// a finger anywhere on the word is "past the end of the text".
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldToDefineTest {
    /**
     * The magnifier a hold on selectable text raises needs a surface, which
     * Robolectric does not give it: showing and hiding do nothing here.
     */
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

    private val defined = mutableListOf<String>()

    private fun render(reading: ReadingSettings = ReadingSettings()) {
        val blocks = MarkdownParser.parseNote("serendipity is a word\n").blocks
        val actions = RenderActions(inline = InlineActions(onDefine = { defined += it }))
        compose.setContent {
            CompositionLocalProvider(LocalReading provides reading) {
                SelectionContainer { blocks.forEach { MdBlockView(it, actions, emptySet()) } }
            }
        }
    }

    // A few pixels into the first word, which starts the line.
    private val onFirstWord = Offset(12f, 0f)

    @Test
    fun `holding a word hands over that word`() {
        render()

        compose.onNodeWithText("serendipity", substring = true).performTouchInput {
            longClick(centerLeft + onFirstWord)
        }

        assertThat(defined).containsExactly("serendipity")
    }

    @Test
    fun `a tap is not a hold`() {
        render()

        compose.onNodeWithText("serendipity", substring = true).performTouchInput {
            click(centerLeft + onFirstWord)
        }

        assertThat(defined).isEmpty()
    }

    @Test
    fun `with the setting off, a hold only selects`() {
        render(ReadingSettings(holdToDefine = false))

        compose.onNodeWithText("serendipity", substring = true).performTouchInput {
            longClick(centerLeft + onFirstWord)
        }

        assertThat(defined).isEmpty()
    }
}
