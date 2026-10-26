package me.parham1995.notes.markdown

import kotlin.math.roundToInt

/**
 * How long a note is and how long it takes to read, as the bar above a note
 * on the Mac says it: the words, and minutes at 220 words a minute.
 *
 * Counted over what would be read out ([SpokenText]), so front matter, code
 * and diagrams do not make a note look longer than it reads.
 */
data class ReadingTime(
    val words: Int,
    val minutes: Int,
) {
    companion object {
        /** Null for a note too short for the number to tell anyone anything. */
        fun of(blocks: List<MdBlock>): ReadingTime? {
            val words = SpokenText.of(blocks).sumOf { words(it.text) }
            if (words < SHORTEST) return null
            return ReadingTime(words, maxOf(1, (words.toDouble() / PER_MINUTE).roundToInt()))
        }

        /**
         * The words in [text]: runs of letters and digits, with an apostrophe
         * or a Persian zero-width non-joiner inside one word -- "don't" and
         * "می‌خواهم" are a word each.
         */
        fun words(text: String): Int = WORD.findAll(text).count()

        private val WORD = Regex("""[\p{L}\p{N}\p{M}]+(?:['’\x{200C}][\p{L}\p{N}\p{M}]+)*""")
        private const val PER_MINUTE = 220.0
        private const val SHORTEST = 50
    }
}
