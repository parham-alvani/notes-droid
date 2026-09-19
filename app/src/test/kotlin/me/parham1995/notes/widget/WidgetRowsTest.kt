package me.parham1995.notes.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * How a widget decides how much to show.
 *
 * Both widgets drew a fixed four or five rows whatever size they were given,
 * so one resized to half a home screen showed the same four lines as a small
 * one and left the rest empty. Found by looking at the home screen it had been
 * sitting on.
 */
class WidgetRowsTest {
    @Test
    fun `a tall widget is filled`() {
        // 344dp is what the home screen on the test device hands out.
        assertThat(rowsForHeight(344)).isEqualTo(16)
    }

    @Test
    fun `a small widget shows a few`() {
        assertThat(rowsForHeight(110)).isEqualTo(3)
    }

    @Test
    fun `an enormous widget stops somewhere sensible`() {
        // Not unbounded: every row is a database row and a PendingIntent.
        assertThat(rowsForHeight(2000)).isEqualTo(18)
    }

    @Test
    fun `no size yet means the minimum rather than none`() {
        // The first update can arrive before the host has said anything about
        // size, and a widget that renders empty then looks broken.
        assertThat(rowsForHeight(0)).isEqualTo(3)
    }

    @Test
    fun `a height too small for even one row still shows the minimum`() {
        assertThat(rowsForHeight(20)).isEqualTo(3)
    }
}
