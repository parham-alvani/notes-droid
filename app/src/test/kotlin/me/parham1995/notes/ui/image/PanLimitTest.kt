package me.parham1995.notes.ui.image

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * How far a zoomed image may be dragged.
 *
 * The arithmetic is small and the failure is not: a limit that comes out
 * negative makes `coerceIn` throw, and one that comes out too large lets the
 * image be pushed off its own screen with no obvious way back.
 */
class PanLimitTest {
    @Test
    fun `a fitted image cannot be dragged at all`() {
        assertThat(panLimit(frameSize = 1080, scale = 1f)).isEqualTo(0f)
    }

    @Test
    fun `below fitted the limit is zero, never negative`() {
        // coerceIn(-limit, limit) throws when the range is inverted, so this
        // is the difference between a clamp and a crash.
        assertThat(panLimit(frameSize = 1080, scale = 0.5f)).isEqualTo(0f)
    }

    @Test
    fun `at twice the size half a frame hangs outside it`() {
        // Doubling leaves one frame's worth outside, half on each side.
        assertThat(panLimit(frameSize = 1080, scale = 2f)).isEqualTo(540f)
    }

    @Test
    fun `the limit grows with the zoom`() {
        assertThat(panLimit(1080, 3f)).isEqualTo(1080f)
        assertThat(panLimit(1080, 6f)).isEqualTo(2700f)
    }

    @Test
    fun `a frame with no size yields no slack`() {
        // The first frame, before layout has measured anything.
        assertThat(panLimit(frameSize = 0, scale = 4f)).isEqualTo(0f)
    }
}
