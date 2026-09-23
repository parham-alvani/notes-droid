package me.parham1995.notes.feature.note

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A Markdown link with no scheme is somewhere in the vault, and this says where. */
class LinkRouteTest {
    // What the index resolved when the note was opened, keyed as the parser
    // hands targets to it: decoded, without the heading.
    private val targets = mapOf("Other Note.md" to 7L, "folder/Note" to 9L)

    private fun route(destination: String) = routeOf(destination, "Projects/Plan.md") { targets[it] }

    @Test
    fun `an encoded note name opens that note at its heading`() {
        assertThat(route("Other%20Note.md#Some%20Part")).isEqualTo(LinkRoute.Note(7, "Some Part"))
        assertThat(route("folder/Note")).isEqualTo(LinkRoute.Note(9, null))
    }

    @Test
    fun `a bare anchor is a heading in this note`() {
        assertThat(route("#Details")).isEqualTo(LinkRoute.Here("Details"))
    }

    @Test
    fun `a file is opened from the vault, relative to the note`() {
        assertThat(route("../uploads/scan%201.pdf")).isEqualTo(LinkRoute.File("uploads/scan 1.pdf"))
    }

    @Test
    fun `anything else is said to go nowhere`() {
        assertThat(route("Never%20Written")).isEqualTo(LinkRoute.Nowhere("Never Written"))
    }
}
