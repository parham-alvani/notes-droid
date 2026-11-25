package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A block wider than the prose, and where it stands.
 *
 * The first six tests are the ones every Daftar app must have
 * (`wide-blocks.md` in notes-linux's `docs/specs/`), by the spec's names: the
 * text size 16, `text_width = 35` (a measure of 560), 40 at each side and
 * `block_width = 60` (960).
 */
class WideBlockTest {
    private fun place(
        natural: Float,
        pane: Float,
    ) = WideBlock.place(natural, measure = 560f, pane = pane, side = 40f, limit = 960f)

    @Test
    fun aBlockThatFitsKeepsTheMeasure() {
        assertThat(place(500f, 1400f)).isNull()
        assertThat(place(560f, 1400f)).isNull()
    }

    @Test
    fun aWideBlockGrowsToItsOwnWidth() {
        assertThat(place(800f, 1400f)).isEqualTo(WideBlock(800f, 300f))
    }

    @Test
    fun theLimitIsTheSettingOrThePane() {
        assertThat(place(1200f, 1400f)).isEqualTo(WideBlock(960f, 220f))
        assertThat(place(1200f, 900f)).isEqualTo(WideBlock(820f, 40f))
    }

    @Test
    fun aNarrowPaneKeepsTheMeasure() {
        assertThat(place(800f, 600f)).isNull()
    }

    @Test
    fun blockWidthZeroIsOff() {
        listOf(600f, 800f, 5000f).forEach { natural ->
            assertThat(WideBlock.place(natural, 560f, 1400f, 40f, limit = 0f)).isNull()
            // `text_width = 0`: no measure to grow past.
            assertThat(WideBlock.place(natural, measure = 0f, pane = 1400f, side = 40f, limit = 960f)).isNull()
        }
        // A `block_width` smaller than `text_width` acts as 0.
        assertThat(WideBlock.place(800f, 560f, 1400f, 40f, limit = 500f)).isNull()
    }

    @Test
    fun whichBlocksAreWide() {
        fun wide(markdown: String) = MarkdownParser.parseNote(markdown).blocks.map { WideBlock.isWide(it) }

        assertThat(wide("| a | b |\n| - | - |\n| 1 | 2 |")).containsExactly(true)
        assertThat(wide("```kotlin\nval x = 1\n```")).containsExactly(true)
        assertThat(wide("```mermaid\ngraph TD\nA-->B\n```")).containsExactly(true)
        assertThat(wide("$$\nx^2\n$$")).containsExactly(true)
        assertThat(wide("![[x.svg]]")).containsExactly(true)
        assertThat(wide("![[x.svg|600]]")).containsExactly(true)
        assertThat(wide("![](x.png)")).containsExactly(true)
        // Lifted out of the sentence, as ever, but not wide.
        assertThat(wide("see ![[x.svg]] here")).containsExactly(false, false, false).inOrder()
        assertThat(wide("> | a | b |\n> | - | - |\n> | 1 | 2 |")).containsExactly(false)
        assertThat(wide("> [!figure] A chart\n>\n> ![[x.svg]]\n>\n> What it shows.")).containsExactly(true)
        assertThat(wide("> [!note]\n>\n> ![[x.svg]]")).containsExactly(false)
        assertThat(wide("Some prose.")).containsExactly(false)
    }

    @Test
    fun `a picture on its own line under a sentence is still alone on its line`() {
        val blocks = MarkdownParser.parseNote("The chart:\n![[x.svg]]\nWhat it shows.").blocks
        assertThat(blocks.map { WideBlock.isWide(it) }).containsExactly(false, true, false).inOrder()
        val twoOnALine = MarkdownParser.parseNote("![[a.png]] ![[b.png]]").blocks
        assertThat(twoOnALine.map { WideBlock.isWide(it) }).containsExactly(false, false)
    }

    @Test
    fun `an svg's width is its width attribute, else its view box`() {
        assertThat(
            WideBlock.svgWidth("""<svg xmlns="http://www.w3.org/2000/svg" width="720" height="300">"""),
        ).isEqualTo(720f)
        assertThat(
            WideBlock.svgWidth("""<?xml version="1.0"?><svg width='812.5px' viewBox="0 0 10 10">"""),
        ).isEqualTo(812.5f)
        assertThat(
            WideBlock.svgWidth("""<svg width="100%" style="max-width: 812px;" viewBox="0 0 812 400">"""),
        ).isEqualTo(812f)
        assertThat(WideBlock.svgWidth("""<svg viewBox="0,0,1440,600">""")).isEqualTo(1440f)
        assertThat(WideBlock.svgWidth("""<svg width="100%">""")).isNull()
        assertThat(WideBlock.svgWidth("not a picture")).isNull()
    }
}
