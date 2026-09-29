package me.parham1995.notes.dictionary

import java.io.Closeable

/**
 * An English dictionary that lives on the phone: Open English WordNet, turned
 * by [WordNetImport] into three files.
 *
 * - **words**: one line per headword, sorted, `word<TAB>n:12,340;v:7`, giving
 *   the byte offsets of its senses for each part of speech in WordNet's order,
 *   which is roughly most common first. Held in memory -- under three
 *   megabytes -- and searched by halving.
 * - **senses**: one line per meaning, `synonyms<TAB>definition<TAB>examples`,
 *   the lists split by `|`. Read a line at a time through [SenseSource].
 * - **exceptions**: `n<TAB>mice<TAB>mouse`, the irregular forms, small enough
 *   to keep in a map.
 */
class Dictionary(
    private val words: ByteArray,
    private val senses: SenseSource,
    exceptions: String,
) : Closeable {
    private val starts: IntArray = lineStarts(words)

    private val exceptions: Map<PartOfSpeech, Map<String, List<String>>> =
        exceptions
            .lineSequence()
            .mapNotNull { line ->
                val (code, form, lemmas) = line.split('\t').takeIf { it.size == 3 } ?: return@mapNotNull null
                PartOfSpeech.of(code.single())?.let { Triple(it, form, lemmas.split('|')) }
            }.groupBy({ it.first }, { it.second to it.third })
            // "leaves" is two lines, leaf and leave: merged, not the last one kept.
            .mapValues { (_, pairs) -> pairs.groupBy({ it.first }, { it.second }).mapValues { it.value.flatten() } }

    /**
     * Every entry [text] could be looking for: the word as it stands and the
     * dictionary forms it inflects, each part of speech its own entry. The
     * word as written comes first -- "running" the noun before run the verb.
     * Empty when nothing matches, which is every Persian word.
     */
    fun define(text: String): List<Entry> {
        for (form in forms(text)) {
            val found = LinkedHashMap<Pair<String, PartOfSpeech>, List<Long>>()
            for (partOfSpeech in PartOfSpeech.entries) {
                for (lemma in Lemmas.candidates(form, partOfSpeech, exceptions[partOfSpeech].orEmpty())) {
                    val offsets = lookup(lemma)?.get(partOfSpeech) ?: continue
                    found.putIfAbsent(lemma to partOfSpeech, offsets)
                }
            }
            if (found.isNotEmpty()) {
                return found.entries
                    .sortedBy { (key, _) -> key.first != form }
                    .map { (key, offsets) -> entry(key.first, key.second, offsets) }
            }
        }
        return emptyList()
    }

    private fun entry(
        lemma: String,
        partOfSpeech: PartOfSpeech,
        offsets: List<Long>,
    ): Entry {
        val read = offsets.map { parseSense(senses.lineAt(it)) }
        // The index is lower case; the senses keep "Einstein" and "DNA" as written.
        val shown = read.flatMap { it.synonyms }.firstOrNull { it.equals(lemma, ignoreCase = true) } ?: lemma
        return Entry(
            word = shown,
            partOfSpeech = partOfSpeech,
            senses =
                read.map { sense ->
                    sense.copy(synonyms = sense.synonyms.filterNot { it.equals(lemma, ignoreCase = true) })
                },
        )
    }

    /** Lets go of the senses file, when that is where they are read from. */
    override fun close() {
        (senses as? Closeable)?.close()
    }

    /** The headword's line, as offsets by part of speech; null when there is no such word. */
    private fun lookup(lemma: String): Map<PartOfSpeech, List<Long>>? {
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            val line = line(middle)
            val tab = line.indexOf('\t')
            val order = line.substring(0, tab).compareTo(lemma)
            when {
                order < 0 -> low = middle + 1
                order > 0 -> high = middle - 1
                else -> return offsetsOf(line.substring(tab + 1))
            }
        }
        return null
    }

    private fun line(index: Int): String {
        val start = starts[index]
        var end = if (index + 1 < starts.size) starts[index + 1] - 1 else words.size
        if (end > start && words[end - 1] == NEWLINE) end -= 1
        return String(words, start, end - start, Charsets.UTF_8)
    }

    companion object {
        const val WORDS = "words"
        const val SENSES = "senses"
        const val EXCEPTIONS = "exceptions"

        private const val NEWLINE = '\n'.code.toByte()

        private fun lineStarts(bytes: ByteArray): IntArray {
            val starts = ArrayList<Int>()
            var start = 0
            var at = 0
            while (at < bytes.size) {
                if (at == start) starts += start
                if (bytes[at] == NEWLINE) start = at + 1
                at += 1
            }
            return starts.toIntArray()
        }

        private fun offsetsOf(field: String): Map<PartOfSpeech, List<Long>> =
            field.split(';').associate { group ->
                val (code, offsets) = group.split(':', limit = 2)
                PartOfSpeech.of(code.single())!! to offsets.split(',').map(String::toLong)
            }

        private fun parseSense(line: String): Sense {
            val fields = line.split('\t')

            fun list(index: Int) =
                fields
                    .getOrNull(index)
                    .orEmpty()
                    .split('|')
                    .filter(String::isNotEmpty)
            return Sense(definition = fields.getOrNull(1).orEmpty(), examples = list(2), synonyms = list(0))
        }

        /**
         * What a selection could be written as in the index: lower case, one
         * space between words, curly apostrophes straight. A selection often
         * takes the punctuation beside a word with it, so the same with that
         * trimmed -- tried second, because "U.S." is a word with its dots.
         */
        internal fun forms(text: String): List<String> {
            val plain =
                text
                    .replace('’', '\'')
                    .replace('‘', '\'')
                    .replace('_', ' ')
                    .replace(WHITESPACE, " ")
                    .trim()
                    .lowercase()
            val trimmed = plain.trim { !it.isLetterOrDigit() }
            val possessive = trimmed.removeSuffix("'s").takeIf { it != trimmed }
            return listOfNotNull(plain, trimmed, possessive).filter(String::isNotEmpty).distinct()
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
