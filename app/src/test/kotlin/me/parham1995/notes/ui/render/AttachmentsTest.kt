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
        assertThat(Attachments.iconOf("Photo.JPG")).isEqualTo("image")
        assertThat(Attachments.iconOf("scan.heic")).isEqualTo("image")
        assertThat(Attachments.iconOf("photo.avif")).isEqualTo("image")
        assertThat(Attachments.iconOf("mystery.wat")).isEqualTo("paperclip")
    }

    @Test
    fun `a picture is shown here and anything else is handed on`() {
        assertThat(Attachments.isImage("uploads/Photo.JPG")).isTrue()
        assertThat(Attachments.isImage("scan.heic")).isTrue()
        assertThat(Attachments.isImage("Manual.pdf")).isFalse()
        assertThat(Attachments.isImage("no-extension")).isFalse()
        // The name of a folder with a dot in it is not the file's type.
        assertThat(Attachments.isImage("v1.png/notes")).isFalse()
    }

    @Test
    fun `an svg is shown here, in the page's theme, not handed to a browser`() {
        assertThat(Attachments.isImage("uploads/diagram.svg")).isTrue()
        assertThat(Attachments.isImage("uploads/DIAGRAM.SVG")).isTrue()
    }

    @Test
    fun `an animated picture is shown here, where it plays`() {
        assertThat(Attachments.isImage("loop.gif")).isTrue()
        assertThat(Attachments.isImage("LOOP.GIF")).isTrue()
        assertThat(Attachments.isImage("loop.webp")).isTrue()
    }

    @Test
    fun `an avif is shown here only where the platform decodes it`() {
        assertThat(Attachments.isImage("photo.avif", sdk = 31)).isTrue()
        assertThat(Attachments.isImage("Photo.AVIF", sdk = 36)).isTrue()
        // Android 11 and before cannot read it, so another app is asked.
        assertThat(Attachments.isImage("photo.avif", sdk = 30)).isFalse()
        assertThat(Attachments.isImage("photo.avif", sdk = 29)).isFalse()
    }
}
