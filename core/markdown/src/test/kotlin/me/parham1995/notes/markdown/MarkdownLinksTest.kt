package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where a `[text](destination)` link goes, when it is not the web. */
class MarkdownLinksTest {
    @Test
    fun `only a scheme makes a link external`() {
        assertThat(MarkdownLinks.isExternal("https://example.com")).isTrue()
        assertThat(MarkdownLinks.isExternal("mailto:someone@example.com")).isTrue()
        assertThat(MarkdownLinks.isExternal("obsidian://open?vault=x")).isTrue()
        assertThat(MarkdownLinks.isExternal("Other%20Note.md")).isFalse()
        assertThat(MarkdownLinks.isExternal("folder/Note")).isFalse()
        assertThat(MarkdownLinks.isExternal("#Heading")).isFalse()
        assertThat(MarkdownLinks.isExternal("../uploads/scan.pdf")).isFalse()
    }

    @Test
    fun `an internal destination is decoded and split from its heading`() {
        assertThat(MarkdownLinks.internal("Other%20Note.md#Some%20Part"))
            .isEqualTo(MarkdownLinks.Internal("Other Note.md", "Some Part"))
        assertThat(MarkdownLinks.internal("folder/Note")).isEqualTo(MarkdownLinks.Internal("folder/Note", null))
        assertThat(MarkdownLinks.internal("#Details")).isEqualTo(MarkdownLinks.Internal("", "Details"))
    }

    @Test
    fun `decoding handles non-Latin names and leaves a plus alone`() {
        assertThat(MarkdownLinks.decode("%DB%8C%D8%A7%D8%AF%D8%AF%D8%A7%D8%B4%D8%AA.md")).isEqualTo("یادداشت.md")
        assertThat(MarkdownLinks.decode("C++%20notes.md")).isEqualTo("C++ notes.md")
        assertThat(MarkdownLinks.decode("100%")).isEqualTo("100%")
        assertThat(MarkdownLinks.decode("50%+off")).isEqualTo("50%+off")
    }

    @Test
    fun `a relative path is resolved from the note's folder`() {
        assertThat(MarkdownLinks.fromNote("../uploads/scan.pdf", "Projects/Plan.md")).isEqualTo("uploads/scan.pdf")
        assertThat(MarkdownLinks.fromNote("./Sub/Note.md", "Projects/Plan.md")).isEqualTo("Projects/Sub/Note.md")
        assertThat(MarkdownLinks.fromNote("uploads/scan.pdf", "Projects/Plan.md")).isEqualTo("uploads/scan.pdf")
    }

    @Test
    fun `a markdown link reaches the index decoded`() {
        val links = MarkdownParser.parseNote("See [the plan](Other%20Note.md#Some%20Part) now.").links
        val link = links.single()
        assertThat(link.kind).isEqualTo(LinkKind.MARKDOWN)
        assertThat(link.rawTarget).isEqualTo("Other Note.md")
        assertThat(link.heading).isEqualTo("Some Part")
    }

    @Test
    fun `a file is told apart from a note`() {
        assertThat(MarkdownLinks.isFile("uploads/scan.pdf")).isTrue()
        assertThat(MarkdownLinks.isFile("Other Note.md")).isFalse()
        assertThat(MarkdownLinks.isFile("folder/Note")).isFalse()
        assertThat(MarkdownLinks.isFile("Release v1.2 notes")).isFalse()
    }
}
