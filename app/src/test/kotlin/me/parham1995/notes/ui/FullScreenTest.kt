package me.parham1995.notes.ui

import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which windows a tap on the page takes full screen in. */
class FullScreenTest {
    @Test
    fun `a tablet or a phone on its side, not a phone held upright`() {
        // 411x891dp at 2.625: a phone upright.
        assertThat(roomyForFullScreen(IntSize(1080, 2340), 2.625f)).isFalse()
        // The same phone on its side.
        assertThat(roomyForFullScreen(IntSize(2340, 1080), 2.625f)).isTrue()
        // A tablet upright, 800dp wide.
        assertThat(roomyForFullScreen(IntSize(1600, 2560), 2f)).isTrue()
    }
}
