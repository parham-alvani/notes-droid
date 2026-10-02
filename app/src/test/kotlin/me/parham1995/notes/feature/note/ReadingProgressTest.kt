package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The hairline under the bar: where it sits for what the list reports. */
class ReadingProgressTest {
    @Test
    fun `a note that fits draws nothing`() {
        val progress =
            readingProgress(
                total = 3,
                last = Extent(index = 2, offset = 400, size = 100),
                viewportEnd = 1000,
                canScrollForward = false,
                canScrollBackward = false,
            )
        assertThat(progress).isNull()
    }

    @Test
    fun `at the end it is full, whatever the last item measures`() {
        val progress =
            readingProgress(
                total = 40,
                last = Extent(index = 39, offset = 700, size = 300),
                viewportEnd = 1000,
                canScrollForward = false,
                canScrollBackward = true,
            )
        assertThat(progress).isEqualTo(1f)
    }

    @Test
    fun `half way down the last item on screen counts as half of it`() {
        // Ten items; the fifth is showing its top half, so four and a half.
        val progress =
            readingProgress(
                total = 10,
                last = Extent(index = 4, offset = 800, size = 400),
                viewportEnd = 1000,
                canScrollForward = true,
                canScrollBackward = true,
            )
        assertThat(progress).isWithin(0.001f).of(0.45f)
    }

    @Test
    fun `an item that overhangs the viewport is not counted past its edge`() {
        val progress =
            readingProgress(
                total = 10,
                last = Extent(index = 4, offset = 1200, size = 400),
                viewportEnd = 1000,
                canScrollForward = true,
                canScrollBackward = true,
            )
        assertThat(progress).isWithin(0.001f).of(0.4f)
    }

    @Test
    fun `an empty list draws nothing`() {
        assertThat(readingProgress(0, null, 1000, canScrollForward = false, canScrollBackward = false)).isNull()
    }
}
