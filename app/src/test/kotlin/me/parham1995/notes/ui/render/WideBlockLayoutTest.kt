package me.parham1995.notes.ui.render

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.LineWidth
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.ui.readingPadding
import me.parham1995.notes.ui.wideRoom
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A table wider than the line width grows past it on a wide window, centred,
 * up to `block_width` em, and the prose keeps the column (`wide-blocks.md`);
 * on a phone held upright nothing moves.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WideBlockLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    /** Ten columns of twelve letters: 960dp of cells, wider than any column the phone keeps. */
    private val note =
        "Some prose above the table, long enough to fill its column and wrap onto a second line.\n\n" +
            (1..10).joinToString(" | ", "| ", " |") { "column$it-abcd" } + "\n" +
            (1..10).joinToString(" | ", "| ", " |") { "-" } + "\n" +
            (1..10).joinToString(" | ", "| ", " |") { "cell$it" } + "\n\n" +
            "> | quoted | table |\n> | - | - |\n> | q1 | q2 |\n"

    private fun render(
        pane: Dp,
        blockWidth: Int = 60,
    ) {
        val blocks = MarkdownParser.parseNote(note).blocks
        compose.setContent {
            MarkdownDocument(
                blocks = blocks,
                actions = RenderActions(),
                contentPadding = readingPadding(pane, LineWidth.COMFORTABLE),
                wideRoom = wideRoom(pane, LineWidth.COMFORTABLE, blockWidth, em = 15.dp),
            )
        }
    }

    /** Where a piece of text starts and ends, in dp from the window's left. */
    private fun span(text: String): ClosedFloatingPointRange<Float> {
        val bounds = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        return bounds.left / density..bounds.right / density
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a wide window a wide table grows past the column, centred, to the limit`() {
        render(1280.dp)
        // The column: 680 wide, from 300 to 980.
        val prose = span("Some prose")
        assertThat(prose.start).isWithin(1f).of(300f)
        assertThat(prose.endInclusive).isAtMost(980.5f)
        // 60 em of 15 is 900, from 190 to 1090: the first cell starts past the
        // column's start, inside the cell's padding.
        assertThat(span("column1-abcd").start).isWithin(2f).of(190f + 8f)
        // The table in the quote keeps the column.
        assertThat(span("quoted").start).isAtLeast(300f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `block width zero keeps the table in the column`() {
        render(1280.dp, blockWidth = 0)
        assertThat(span("column1-abcd").start).isWithin(2f).of(300f + 8f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `on a phone held upright nothing moves`() {
        render(411.dp)
        assertThat(span("Some prose").start).isWithin(1f).of(16f)
        assertThat(span("column1-abcd").start).isWithin(2f).of(16f + 8f)
    }
}
