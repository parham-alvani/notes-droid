package me.parham1995.notes.ui

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.LineWidth
import org.junit.Test

/** The reading column's margins, which are all the line-width setting is. */
class ReadingPaddingTest {
    private fun side(
        available: Int,
        width: LineWidth,
    ) = readingPadding(available.dp, width).calculateLeftPadding(LayoutDirection.Ltr)

    @Test
    fun `a phone keeps its usual margin whatever the setting`() {
        LineWidth.entries.forEach { assertThat(side(411, it)).isEqualTo(16.dp) }
    }

    @Test
    fun `a wide window holds the column to its width, in the middle`() {
        assertThat(side(1280, LineWidth.COMFORTABLE)).isEqualTo(300.dp)
        assertThat(side(1280, LineWidth.NARROW)).isEqualTo(360.dp)
    }

    @Test
    fun `full width is the usual margin at any size`() {
        assertThat(side(1280, LineWidth.FULL)).isEqualTo(16.dp)
    }

    @Test
    fun `just over the width never cuts the margin below the usual one`() {
        assertThat(side(700, LineWidth.COMFORTABLE)).isEqualTo(16.dp)
    }
}
