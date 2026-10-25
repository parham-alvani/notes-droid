package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.database.HeadingEntity
import org.junit.Test

/**
 * Where the page goes as a note arrives and as headings in it are asked for.
 *
 * A jump to `[[#Heading]]` in the note being read used to bounce straight
 * back to where the note had been left, because the restore ran again once
 * the heading was cleared.
 */
class LandingTest {
    private val headings =
        listOf(
            HeadingEntity(noteId = 1, level = 2, text = "Soil", slug = "soil", ordinal = 0, blockIndex = 4),
            HeadingEntity(noteId = 1, level = 2, text = "Water", slug = "water", ordinal = 1, blockIndex = 12),
        )

    private fun land(
        heading: String?,
        first: Boolean,
        resumeAt: Int = 30,
    ) = landing(headings, heading, emptyMap(), resumeAt, firstLanding = first)

    @Test
    fun `a note arrives where it was left`() {
        assertThat(land(heading = null, first = true)).isEqualTo(Landing.To(30))
    }

    @Test
    fun `a note never scrolled arrives at the top, not where the last one was left`() {
        assertThat(land(heading = null, first = true, resumeAt = 0)).isEqualTo(Landing.To(0))
    }

    @Test
    fun `a heading asked for wins over where the note was left`() {
        assertThat(land(heading = "Water", first = true)).isEqualTo(Landing.To(12))
    }

    @Test
    fun `once the heading is done with the page stays at it`() {
        // The heading is cleared after the jump, and the effect runs again.
        assertThat(land(heading = "Soil", first = false)).isEqualTo(Landing.To(4))
        assertThat(land(heading = null, first = false)).isEqualTo(Landing.Stay)
    }

    @Test
    fun `a renamed heading opens the note where it was left, and later asks nothing`() {
        assertThat(land(heading = "Gone", first = true)).isEqualTo(Landing.To(30))
        assertThat(land(heading = "Gone", first = false)).isEqualTo(Landing.Stay)
    }
}
