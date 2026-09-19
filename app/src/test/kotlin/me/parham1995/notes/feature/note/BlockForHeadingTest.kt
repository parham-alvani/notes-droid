package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.database.HeadingEntity
import org.junit.Test

/**
 * Following `[[Note#Heading]]` to the heading it names.
 *
 * 48 links in this vault carry one, and every one of them landed at the top of
 * the note instead: the handler took the heading and discarded it.
 */
class BlockForHeadingTest {
    private fun heading(
        text: String,
        at: Int,
    ) = HeadingEntity(noteId = 1, level = 2, text = text, slug = text.lowercase(), ordinal = at, blockIndex = at)

    private val headings =
        listOf(
            heading("Why CQRS", 12),
            heading("Operational Patterns", 40),
            heading("Read-your-writes — session pinning", 61),
        )

    @Test
    fun `a heading is found by its own text`() {
        assertThat(blockForHeading(headings, "Operational Patterns")).isEqualTo(40)
    }

    @Test
    fun `case does not matter`() {
        // Obsidian does not slugify an anchor, and nobody retypes the case.
        assertThat(blockForHeading(headings, "why cqrs")).isEqualTo(12)
    }

    @Test
    fun `surrounding space does not matter`() {
        assertThat(blockForHeading(headings, "  Why CQRS ")).isEqualTo(12)
    }

    @Test
    fun `punctuation in a heading is matched as written`() {
        // Slugifying would mangle the em dash, and the vault's real headings
        // are full of them.
        assertThat(blockForHeading(headings, "Read-your-writes — session pinning")).isEqualTo(61)
    }

    @Test
    fun `a heading that no longer exists is not an error`() {
        // The note still opens, where it was left.
        assertThat(blockForHeading(headings, "Renamed Since")).isNull()
    }

    @Test
    fun `no heading asked for means no scroll`() {
        assertThat(blockForHeading(headings, null)).isNull()
        assertThat(blockForHeading(headings, "   ")).isNull()
    }
}
