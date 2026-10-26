package me.parham1995.notes.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadingTimeTest {
    private fun blocks(markdown: String) = MarkdownParser.parseNote(markdown).blocks

    @Test
    fun `words are runs of letters, and an apostrophe or a non-joiner stays inside one`() {
        assertThat(ReadingTime.words("Don't stop — it's 3 o'clock")).isEqualTo(5)
        assertThat(ReadingTime.words("من می‌خواهم بروم")).isEqualTo(3)
        assertThat(ReadingTime.words("  \n\t— … ")).isEqualTo(0)
    }

    @Test
    fun `a short note has no reading time`() {
        assertThat(ReadingTime.of(blocks("A few words only."))).isNull()
        assertThat(ReadingTime.of(emptyList())).isNull()
    }

    @Test
    fun `minutes are the words at 220 a minute, never under one`() {
        val fifty = List(50) { "word" }.joinToString(" ")
        assertThat(ReadingTime.of(blocks(fifty))).isEqualTo(ReadingTime(50, 1))

        val long = List(1_000) { "word" }.joinToString(" ")
        assertThat(ReadingTime.of(blocks(long))).isEqualTo(ReadingTime(1_000, 5))
    }

    @Test
    fun `front matter and code are not counted`() {
        val prose = List(60) { "word" }.joinToString(" ")
        val note =
            "---\ntags: [a, b, c, d]\ntitle: many words in the front matter\n---\n\n" +
                "$prose\n\n```\nnot read out at all\n```\n"

        assertThat(ReadingTime.of(blocks(note))?.words).isEqualTo(60)
    }
}
