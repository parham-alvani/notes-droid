package me.parham1995.notes.dictionary

import com.google.common.truth.Truth.assertThat
import org.junit.AfterClass
import org.junit.Test
import java.io.File

/**
 * The dictionary the app actually ships, read the way the app reads it. Catches
 * a regenerated asset that no longer matches the format, which nothing else
 * would until someone selected a word on a phone.
 */
class BundledDictionaryTest {
    @Test
    fun `common words, inflections and names are all there`() {
        assertThat(bundled.define("serendipity").single().senses).isNotEmpty()
        assertThat(bundled.define("mice").map { it.word }).contains("mouse")
        assertThat(bundled.define("running").map { it.word }).containsAtLeast("running", "run")
        assertThat(bundled.define("went").map { it.word }).contains("go")
        assertThat(bundled.define("Einstein").first().word).isEqualTo("Einstein")
    }

    @Test
    fun `a sense reads like a definition, not like a database row`() {
        val sense =
            bundled
                .define("dog")
                .first { it.partOfSpeech == PartOfSpeech.NOUN }
                .senses
                .first()

        assertThat(sense.definition).contains("domesticated")
        assertThat(sense.definition).doesNotContain("\"")
        assertThat(sense.examples).isNotEmpty()
        assertThat(sense.synonyms).contains("domestic dog")
    }

    companion object {
        private val dir = File("../../app/src/main/assets/dictionary")
        private val senses =
            FileSenseSource(File.createTempFile("senses", null).apply { deleteOnExit() }.also(::unpack))
        private val bundled =
            Dictionary(
                words = read(Dictionary.WORDS),
                senses = senses,
                exceptions = String(read(Dictionary.EXCEPTIONS), Charsets.UTF_8),
            )

        private fun read(name: String) = File(dir, name).readBytes()

        private fun unpack(to: File) = to.writeBytes(read(Dictionary.SENSES))

        @JvmStatic
        @AfterClass
        fun close() = senses.close()
    }
}
