package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.ui.render.FindTarget
import org.junit.Test

/** Which hit find in the note is on, and where the page goes for it. */
class FindStepsTest {
    // Two hits in block 3, one in 8, two in 20.
    private val blocks = listOf(3, 3, 8, 20, 20)

    @Test
    fun `find starts at the first hit at or below the top of the screen`() {
        assertThat(findStart(blocks, fromBlock = 0)).isEqualTo(0)
        assertThat(findStart(blocks, fromBlock = 8)).isEqualTo(2)
        assertThat(findStart(blocks, fromBlock = 9)).isEqualTo(3)
    }

    @Test
    fun `with every hit above the screen it starts at the first`() {
        assertThat(findStart(blocks, fromBlock = 50)).isEqualTo(0)
        assertThat(findStart(emptyList(), fromBlock = 5)).isEqualTo(0)
    }

    @Test
    fun `stepping goes round at both ends`() {
        assertThat(stepFind(at = 4, size = 5, direction = 1)).isEqualTo(0)
        assertThat(stepFind(at = 0, size = 5, direction = -1)).isEqualTo(4)
        assertThat(stepFind(at = 2, size = 5, direction = 1)).isEqualTo(3)
        assertThat(stepFind(at = 0, size = 0, direction = 1)).isEqualTo(0)
    }

    @Test
    fun `the target names the hit's place among its own block's hits`() {
        assertThat(findTarget("x", blocks, 0)).isEqualTo(FindTarget("x", block = 3, ordinal = 0))
        assertThat(findTarget("x", blocks, 1)).isEqualTo(FindTarget("x", block = 3, ordinal = 1))
        assertThat(findTarget("x", blocks, 2)).isEqualTo(FindTarget("x", block = 8, ordinal = 0))
        assertThat(findTarget("x", blocks, 4)).isEqualTo(FindTarget("x", block = 20, ordinal = 1))
    }

    @Test
    fun `a blank query marks nothing`() {
        assertThat(findTarget(" ", blocks, 0)).isNull()
    }

    @Test
    fun `the page moves only for a hit that is not already in view`() {
        // A viewport from 0 to 900.
        assertThat(findNeedsScroll(top = null, viewportStart = 0, viewportEnd = 900)).isTrue()
        assertThat(findNeedsScroll(top = 100, viewportStart = 0, viewportEnd = 900)).isFalse()
        assertThat(findNeedsScroll(top = -300, viewportStart = 0, viewportEnd = 900)).isFalse()
        assertThat(findNeedsScroll(top = 800, viewportStart = 0, viewportEnd = 900)).isTrue()
    }

    @Test
    fun `a hit far down in a block on screen still brings the page to it`() {
        // A page 900 high: a hit above it, or in its lower third, moves it.
        assertThat(findHitNeedsScroll(top = 2400f, bottom = 2440f, height = 900)).isTrue()
        assertThat(findHitNeedsScroll(top = -500f, bottom = -460f, height = 900)).isTrue()
        assertThat(findHitNeedsScroll(top = 700f, bottom = 740f, height = 900)).isTrue()
        assertThat(findHitNeedsScroll(top = 300f, bottom = 340f, height = 900)).isFalse()
    }
}
