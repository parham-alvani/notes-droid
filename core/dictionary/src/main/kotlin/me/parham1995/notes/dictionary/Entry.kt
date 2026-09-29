package me.parham1995.notes.dictionary

/** WordNet's four parts of speech, by the letter its files use for each. */
enum class PartOfSpeech(
    val code: Char,
) {
    NOUN('n'),
    VERB('v'),
    ADJECTIVE('a'),
    ADVERB('r'),
    ;

    companion object {
        fun of(code: Char): PartOfSpeech? = entries.firstOrNull { it.code == code }
    }
}

/** One word as one part of speech, and every sense it has as that, most common first. */
data class Entry(
    val word: String,
    val partOfSpeech: PartOfSpeech,
    val senses: List<Sense>,
)

/** A meaning, with the other words that share it -- not counting the one looked up. */
data class Sense(
    val definition: String,
    val examples: List<String>,
    val synonyms: List<String>,
)
