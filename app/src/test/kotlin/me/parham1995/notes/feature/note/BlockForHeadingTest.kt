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

    @Test
    fun `a block id lands on the block that carries it`() {
        assertThat(blockForHeading(headings, "^quote-1", mapOf("quote-1" to 17))).isEqualTo(17)
        assertThat(blockForHeading(headings, "^gone", mapOf("quote-1" to 17))).isNull()
    }

    @Test
    fun `a heading path lands on the child under the parent it names`() {
        // Two sections called Notes; the path says which.
        val chapters =
            listOf(
                HeadingEntity(noteId = 1, level = 1, text = "One", slug = "one", ordinal = 0, blockIndex = 0),
                HeadingEntity(noteId = 1, level = 2, text = "Notes", slug = "notes", ordinal = 1, blockIndex = 3),
                HeadingEntity(noteId = 1, level = 1, text = "Two", slug = "two", ordinal = 2, blockIndex = 9),
                HeadingEntity(noteId = 1, level = 2, text = "Notes", slug = "notes", ordinal = 3, blockIndex = 12),
            )
        assertThat(blockForHeading(chapters, "Two#Notes")).isEqualTo(12)
        assertThat(blockForHeading(chapters, "Notes")).isEqualTo(3)
    }
}
