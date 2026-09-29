package me.parham1995.notes.dictionary

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WordAtTest {
    private val text = "Don't stop, it's well-known: 'quoted' and می‌روم 42."

    private fun at(word: String) = wordAt(text, text.indexOf(word) + 1)

    @Test
    fun `the word around a letter, from anywhere inside it`() {
        assertThat(wordAt(text, text.indexOf("stop"))).isEqualTo("stop")
        assertThat(wordAt(text, text.indexOf("stop") + 3)).isEqualTo("stop")
    }

    @Test
    fun `an apostrophe or hyphen between letters is part of the word`() {
        assertThat(at("Don't")).isEqualTo("Don't")
        assertThat(at("well-known")).isEqualTo("well-known")
        assertThat(at("known")).isEqualTo("well-known")
    }

    @Test
    fun `quotes and punctuation around a word are not`() {
        assertThat(at("quoted")).isEqualTo("quoted")
        assertThat(wordAt(text, text.indexOf("stop,") + 4)).isNull()
    }

    @Test
    fun `a Persian word holds together across its non-joiner`() {
        assertThat(at("می")).isEqualTo("می‌روم")
    }

    @Test
    fun `a space, a number or a place past the end is no word`() {
        assertThat(wordAt(text, text.indexOf(' '))).isNull()
        assertThat(wordAt(text, text.indexOf("42"))).isNull()
        assertThat(wordAt(text, text.length)).isNull()
        assertThat(wordAt("", 0)).isNull()
    }
}
