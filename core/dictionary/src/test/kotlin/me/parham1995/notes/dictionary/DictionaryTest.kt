package me.parham1995.notes.dictionary

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DictionaryTest {
    // A tiny WordNet in the real files' shape, header and all.
    private val wordnet =
        mapOf(
            "data.noun" to
                listOf(
                    "  1 This software and database is being provided to you",
                    "00000100 04 n 01 run 0 000 | a score in baseball made by a runner touching all four bases; " +
                        "\"the Yankees scored 3 runs\"",
                    "00000200 06 n 02 mouse 0 computer_mouse 0 001 @ 00000100 n 0000 | a hand-operated device; " +
                        "\"he clicked the mouse\"; \"a wireless mouse\"",
                    "00000300 10 n 01 running 0 000 | the act of running",
                    "00000400 10 n 01 A 0 000 | the first letter of the alphabet, written \"a\"",
                    "00000500 18 n 01 Einstein 0 000 | physicist born in Germany",
                    "00000550 20 n 01 leaf 0 000 | the flattened green part of a plant",
                    "00000560 28 n 01 leave 0 000 | permission to do something",
                ),
            "index.noun" to
                listOf(
                    "  1 This software and database is being provided to you",
                    "a n 1 0 1 0 00000400",
                    "computer_mouse n 1 0 1 0 00000200",
                    "einstein n 1 0 1 0 00000500",
                    "leaf n 1 0 1 0 00000550",
                    "leave n 1 0 1 0 00000560",
                    "mouse n 1 1 @ 1 0 00000200",
                    "run n 1 0 1 0 00000100",
                    "running n 1 0 1 0 00000300",
                ),
            "noun.exc" to listOf("mice mouse", "leaves leaf", "leaves leave"),
            "data.verb" to
                listOf(
                    "00000600 38 v 02 run 0 go_fast 0 000 01 + 02 00 | move fast by using one's feet; " +
                        "\"don't run in the halls\"--the teacher",
                    "00000700 38 v 01 stop 0 000 | come to a halt",
                ),
            "index.verb" to listOf("run v 1 0 1 0 00000600", "stop v 1 0 1 0 00000700"),
            "data.adj" to listOf("00000800 00 a 01 big(a) 0 000 | above average in size; \"a big car\""),
            "index.adj" to listOf("big a 1 0 1 0 00000800"),
            "adj.exc" to listOf("bigger big"),
        )

    private val converted = WordNetImport.convert { wordnet[it].orEmpty() }
    private val dictionary = dictionaryOf(converted)

    @Test
    fun `a word is found with its definition, examples and synonyms`() {
        val entry = dictionary.define("mouse").single()

        assertThat(entry.word).isEqualTo("mouse")
        assertThat(entry.partOfSpeech).isEqualTo(PartOfSpeech.NOUN)
        val sense = entry.senses.single()
        assertThat(sense.definition).isEqualTo("a hand-operated device")
        assertThat(sense.examples).containsExactly("he clicked the mouse", "a wireless mouse").inOrder()
        assertThat(sense.synonyms).containsExactly("computer mouse")
    }

    @Test
    fun `each part of speech is its own entry`() {
        assertThat(dictionary.define("run").map { it.partOfSpeech })
            .containsExactly(PartOfSpeech.NOUN, PartOfSpeech.VERB)
            .inOrder()
    }

    @Test
    fun `an irregular form finds its word through the exceptions`() {
        assertThat(dictionary.define("mice").map { it.word }).containsExactly("mouse")
        assertThat(dictionary.define("bigger").map { it.word }).containsExactly("big")
    }

    @Test
    fun `a form with two irregular sources finds both`() {
        // WordNet lists these as two lines, one per source.
        assertThat(dictionary.define("leaves").map { it.word }).containsExactly("leaf", "leave")
    }

    @Test
    fun `a regular inflection finds its word, doubled consonant and all`() {
        assertThat(dictionary.define("stopped").map { it.word }).containsExactly("stop")
        assertThat(dictionary.define("runs").map { it.word to it.partOfSpeech })
            .containsExactly("run" to PartOfSpeech.NOUN, "run" to PartOfSpeech.VERB)
    }

    @Test
    fun `the word as written comes before the word it inflects`() {
        assertThat(dictionary.define("running").map { it.word to it.partOfSpeech })
            .containsExactly("running" to PartOfSpeech.NOUN, "run" to PartOfSpeech.VERB)
            .inOrder()
    }

    @Test
    fun `a selection's punctuation, case and spacing are forgiven`() {
        assertThat(dictionary.define("  Mice,").map { it.word }).containsExactly("mouse")
        assertThat(dictionary.define("computer\n mouse").map { it.word }).containsExactly("computer mouse")
        assertThat(dictionary.define("mouse’s").map { it.word }).containsExactly("mouse")
    }

    @Test
    fun `a name keeps its capital`() {
        assertThat(dictionary.define("einstein").single().word).isEqualTo("Einstein")
    }

    @Test
    fun `a quote inside a definition stays in it`() {
        val sense =
            dictionary
                .define("a")
                .single()
                .senses
                .single()

        assertThat(sense.definition).isEqualTo("the first letter of the alphabet, written \"a\"")
        assertThat(sense.examples).isEmpty()
    }

    @Test
    fun `what follows an example stays with it`() {
        val verb = dictionary.define("run").single { it.partOfSpeech == PartOfSpeech.VERB }

        assertThat(verb.senses.single().examples).containsExactly("don't run in the halls--the teacher")
        assertThat(verb.senses.single().synonyms).containsExactly("go fast")
    }

    @Test
    fun `an adjective's position marker is not part of it`() {
        assertThat(dictionary.define("big").single().word).isEqualTo("big")
    }

    @Test
    fun `nothing is found for a word that is not there`() {
        assertThat(dictionary.define("zebra")).isEmpty()
        assertThat(dictionary.define("کتاب")).isEmpty()
        assertThat(dictionary.define("  ")).isEmpty()
    }

    @Test
    fun `every word in a long list is found, the first and the last too`() {
        val many = (0 until 1000).map { "w%04d".format(it) }
        val big =
            WordNetImport.convert { name ->
                when (name) {
                    "data.noun" -> many.mapIndexed { i, w -> "%08d 04 n 01 $w 0 000 | sense $i".format(i + 1) }
                    "index.noun" -> many.mapIndexed { i, w -> "$w n 1 0 1 0 %08d".format(i + 1) }
                    else -> emptyList()
                }
            }
        val lookup = dictionaryOf(big)

        many.forEachIndexed { i, word ->
            assertThat(
                lookup
                    .define(word)
                    .single()
                    .senses
                    .single()
                    .definition,
            ).isEqualTo("sense $i")
        }
        assertThat(lookup.define("w")).isEmpty()
        assertThat(lookup.define("w9999")).isEmpty()
    }

    private fun dictionaryOf(converted: WordNetImport.Converted): Dictionary {
        val senses = converted.senses.toByteArray(Charsets.UTF_8)
        return Dictionary(
            words = converted.words.toByteArray(Charsets.UTF_8),
            senses = { offset ->
                val end = (offset.toInt() until senses.size).first { senses[it] == '\n'.code.toByte() }
                String(senses, offset.toInt(), end - offset.toInt(), Charsets.UTF_8)
            },
            exceptions = converted.exceptions,
        )
    }
}
