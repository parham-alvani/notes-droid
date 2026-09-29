package me.parham1995.notes.dictionary

/**
 * The dictionary forms an inflected word might have come from: WordNet's own
 * "morphy", which is what makes "mice" find mouse and "bigger" find big.
 *
 * Irregular forms come from the exception lists WordNet ships. Regular ones
 * are guessed by swapping endings, and a guess is only as good as the index
 * agreeing -- "glasses" yields "glass" and "glasse" and "glasses", and only
 * the words that exist are kept by the caller. One step beyond morphy:
 * a doubled consonant is undone, so "running" and "stopped" reach run and
 * stop without either being listed as an exception.
 */
internal object Lemmas {
    private val RULES =
        mapOf(
            PartOfSpeech.NOUN to
                listOf(
                    "s" to "",
                    "ses" to "s",
                    "xes" to "x",
                    "zes" to "z",
                    "ches" to "ch",
                    "shes" to "sh",
                    "men" to "man",
                    "ies" to "y",
                ),
            PartOfSpeech.VERB to
                listOf(
                    "s" to "",
                    "ies" to "y",
                    "es" to "e",
                    "es" to "",
                    "ed" to "e",
                    "ed" to "",
                    "ing" to "e",
                    "ing" to "",
                ),
            PartOfSpeech.ADJECTIVE to
                listOf(
                    "er" to "",
                    "est" to "",
                    "er" to "e",
                    "est" to "e",
                ),
            PartOfSpeech.ADVERB to emptyList(),
        )

    fun candidates(
        word: String,
        partOfSpeech: PartOfSpeech,
        exceptions: Map<String, List<String>>,
    ): List<String> =
        buildList {
            add(word)
            exceptions[word]?.let(::addAll)
            RULES.getValue(partOfSpeech).forEach { (suffix, ending) ->
                if (word.length > suffix.length && word.endsWith(suffix)) {
                    val stem = word.dropLast(suffix.length)
                    add(stem + ending)
                    if (ending.isEmpty() && doubled(stem)) add(stem.dropLast(1))
                }
            }
        }.distinct()

    /** "runn", "stopp", "bigg": the spelling rule that doubles a final consonant. */
    private fun doubled(stem: String): Boolean =
        stem.length >= 3 &&
            stem.last() == stem[stem.length - 2] &&
            stem.last().isLetter() &&
            stem.last() !in "aeiou"
}
