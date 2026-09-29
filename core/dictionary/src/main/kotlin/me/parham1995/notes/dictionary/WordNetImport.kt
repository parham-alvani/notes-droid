package me.parham1995.notes.dictionary

import java.io.File

/**
 * Turns a WordNet database in its classic form (WNDB: `index.noun`,
 * `data.noun`, `noun.exc` and so on for each part of speech) into the three
 * files [Dictionary] reads.
 *
 * Kept from WordNet: every headword, and for each sense its definition,
 * examples and synonyms. Dropped: the relations between senses -- hypernyms,
 * antonyms, verb frames -- which are most of the database and not what a
 * reader looking up a word wants. That is the difference between thirty
 * megabytes and fourteen, which the APK deflates to five.
 *
 * Written as plain text, not gzipped. The APK deflates its assets anyway, and
 * the build tools quietly unpack an asset named `.gz` and drop the extension
 * -- so a gzipped file is not what arrives on the phone under that name.
 * Deterministic: the same release produces the same bytes.
 */
object WordNetImport {
    private val FILES =
        mapOf(
            PartOfSpeech.NOUN to "noun",
            PartOfSpeech.VERB to "verb",
            PartOfSpeech.ADJECTIVE to "adj",
            PartOfSpeech.ADVERB to "adv",
        )

    class Converted(
        val words: String,
        val senses: String,
        val exceptions: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: WordNetImport <wordnet directory> <output directory>" }
        val source = File(args[0])
        val out = File(args[1]).apply { mkdirs() }
        val converted = convert { name -> File(source, name).takeIf(File::exists)?.readLines().orEmpty() }
        write(File(out, Dictionary.WORDS), converted.words)
        write(File(out, Dictionary.SENSES), converted.senses)
        write(File(out, Dictionary.EXCEPTIONS), converted.exceptions)
        println(
            "${converted.words.lineSequence().count(
                String::isNotEmpty,
            )} words, ${converted.senses.lineSequence().count(String::isNotEmpty)} senses",
        )
    }

    /** [read] gives a WNDB file's lines by its name, or nothing when it is absent. */
    fun convert(read: (String) -> List<String>): Converted {
        val senses = StringBuilder()
        var bytes = 0L
        // "n:00001740" -> where that synset's line starts in the senses file.
        val offsets = HashMap<String, Long>()
        FILES.forEach { (partOfSpeech, name) ->
            read("data.$name").filter { it.firstOrNull()?.isDigit() == true }.forEach { line ->
                val (id, text) = sense(line)
                offsets["${partOfSpeech.code}:$id"] = bytes
                senses.append(text).append('\n')
                bytes += text.toByteArray(Charsets.UTF_8).size + 1
            }
        }

        val words = sortedMapOf<String, MutableList<String>>()
        FILES.forEach { (partOfSpeech, name) ->
            read("index.$name").filter { it.isNotEmpty() && !it.startsWith(' ') }.forEach { line ->
                val fields = line.trim().split(' ')
                val count = fields[2].toInt()
                val found = fields.takeLast(count).map { offsets.getValue("${partOfSpeech.code}:$it") }
                words
                    .getOrPut(clean(fields[0].replace('_', ' '))) { mutableListOf() }
                    .add("${partOfSpeech.code}:${found.joinToString(",")}")
            }
        }

        val exceptions =
            FILES.flatMap { (partOfSpeech, name) ->
                read("$name.exc").mapNotNull { line ->
                    val fields = line.trim().split(' ').map { it.replace('_', ' ') }
                    if (fields.size < 2) return@mapNotNull null
                    "${partOfSpeech.code}\t${fields[0]}\t${fields.drop(1).joinToString("|")}"
                }
            }

        return Converted(
            words =
                words.entries.joinToString(
                    "\n",
                    postfix = "\n",
                ) { (word, groups) -> "$word\t${groups.joinToString(";")}" },
            senses = senses.toString(),
            exceptions = exceptions.joinToString("\n", postfix = "\n"),
        )
    }

    /** A `data.*` line as its synset id and the senses-file line for it. */
    internal fun sense(line: String): Pair<String, String> {
        val (head, gloss) = line.split(" | ", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val fields = head.split(' ').filter(String::isNotEmpty)
        val count = fields[3].toInt(16)
        val synonyms =
            (0 until count).map { index ->
                // An adjective can carry where it may stand: "galore(ip)".
                clean(fields[4 + 2 * index].replace('_', ' ').replace(MARKER, ""))
            }
        val (definition, examples) = gloss(gloss.trim())
        return fields[0] to
            listOf(synonyms.joinToString("|"), definition, examples.joinToString("|")).joinToString("\t")
    }

    /**
     * A gloss is the definition and then its examples, each in double quotes,
     * all separated by semicolons. A quote inside the definition --
     * `the letter "a"` -- is left where it is: only a part that starts with a
     * quote is an example.
     */
    internal fun gloss(text: String): Pair<String, List<String>> {
        val definition = mutableListOf<String>()
        val examples = mutableListOf<String>()
        text.split("; ").map(String::trim).filter(String::isNotEmpty).forEach { part ->
            when {
                part.startsWith('"') -> examples += unquote(part)
                examples.isEmpty() -> definition += part
                // Words after an example belong to it: who said it, usually.
                else -> examples[examples.lastIndex] = examples.last() + "; " + part
            }
        }
        return clean(definition.joinToString("; ")) to examples.map(::clean)
    }

    private fun unquote(part: String): String {
        val open = part.removePrefix("\"")
        val close = open.lastIndexOf('"')
        return if (close < 0) open else open.removeRange(close, close + 1)
    }

    /** No field may hold what separates fields. */
    private fun clean(text: String): String = text.replace(SEPARATORS, " ").trim()

    private fun write(
        file: File,
        text: String,
    ) = file.writeText(text, Charsets.UTF_8)

    private val MARKER = Regex("\\([a-z]+\\)$")
    private val SEPARATORS = Regex("[\\t|\\n\\r]")
}
