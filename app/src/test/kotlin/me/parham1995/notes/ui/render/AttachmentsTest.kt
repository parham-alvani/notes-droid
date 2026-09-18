package me.parham1995.notes.ui.render

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AttachmentsTest {
    @Test
    fun `a pdf is offered as a pdf, not as a wildcard`() {
        // The whole point of the table: a wildcard type opens a chooser
        // containing every app on the phone rather than the one that can read
        // the file.
        assertThat(Attachments.mimeTypeOf("Manual.pdf")).isEqualTo("application/pdf")
    }

    @Test
    fun `the types this vault holds all resolve`() {
        assertThat(Attachments.mimeTypeOf("clip.mp4")).isEqualTo("video/mp4")
        assertThat(Attachments.mimeTypeOf("voice.ogg")).isEqualTo("audio/ogg")
        assertThat(Attachments.mimeTypeOf("sheet.xlsx"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertThat(Attachments.mimeTypeOf("diagram.drawio")).isEqualTo("application/xml")
    }

    @Test
    fun `extension case does not matter`() {
        assertThat(Attachments.mimeTypeOf("MANUAL.PDF")).isEqualTo("application/pdf")
    }

    @Test
    fun `a name with dots in it uses the last one`() {
        assertThat(Attachments.mimeTypeOf("2026.09.18 meeting.pdf")).isEqualTo("application/pdf")
    }

    @Test
    fun `something unrecognised falls back rather than failing`() {
        assertThat(Attachments.mimeTypeOf("notes.wat")).isEqualTo("*/*")
        assertThat(Attachments.mimeTypeOf("README")).isEqualTo("*/*")
        assertThat(Attachments.mimeTypeOf("")).isEqualTo("*/*")
    }

    @Test
    fun `each kind of attachment gets its own glyph`() {
        assertThat(Attachments.iconOf("clip.mp4")).isEqualTo("film")
        assertThat(Attachments.iconOf("voice.ogg")).isEqualTo("music")
        assertThat(Attachments.iconOf("Manual.pdf")).isEqualTo("file-text")
        assertThat(Attachments.iconOf("books.epub")).isEqualTo("book-open-text")
        assertThat(Attachments.iconOf("archive.zip")).isEqualTo("file-archive")
        assertThat(Attachments.iconOf("mystery.wat")).isEqualTo("paperclip")
    }
}
