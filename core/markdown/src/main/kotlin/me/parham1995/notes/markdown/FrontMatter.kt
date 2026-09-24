package me.parham1995.notes.markdown

/**
 * One front matter property, as Obsidian's Properties view lists it.
 *
 * [values] holds one entry for a plain value and one per item for a list;
 * [isList] says which, since a list of one and a string are drawn differently.
 */
data class FrontMatterProperty(
    val key: String,
    val values: List<String>,
    val isList: Boolean = false,
)

/**
 * Reads the YAML at the top of a note, as much of YAML as notes actually use.
 *
 * commonmark's extension does the job of taking the block out of the body, but
 * what it hands back loses too much to show: a key with a space in it -- which
 * Obsidian's own property editor writes -- is dropped outright, `[a, b]` is one
 * string, and a nested value's lines come back as keys of their own. So the
 * values are read here, from the source, and the extension is kept only to
 * decide that there is front matter at all.
 *
 * Covered: `key: value`, quoted values, `key: [a, b]`, a `- item` list under a
 * key, and `|` / `>` blocks. Anything else indented under a key is kept as its
 * lines rather than interpreted, which is wrong only for maps inside maps.
 */
object FrontMatter {
    fun parse(markdown: String): List<FrontMatterProperty> {
        val lines = markdown.lineSequence().toList()
        if (lines.firstOrNull()?.trimEnd() != FENCE) return emptyList()
        val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == FENCE || lines[it].trimEnd() == "..." }
        if (end == null) return emptyList()
        return properties(lines.subList(1, end))
    }

    private fun properties(lines: List<String>): List<FrontMatterProperty> {
        val out = mutableListOf<FrontMatterProperty>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            index++
            if (line.isBlank() || line.trimStart().startsWith('#') || line.first().isWhitespace()) continue
            val match = KEY.matchEntire(line.trimEnd()) ?: continue
            val key = unquote(match.groupValues[1].trim())
            val value = match.groupValues[2].trim()

            // Everything indented under the key, or a list written flush
            // against it, belongs to it.
            val nested = mutableListOf<String>()
            while (index < lines.size) {
                val next = lines[index]
                val belongs =
                    next.isBlank() ||
                        next.first().isWhitespace() ||
                        next.trimStart().startsWith("- ") ||
                        next.trim() == "-"
                if (!belongs) break
                nested += next
                index++
            }
            while (nested.isNotEmpty() && nested.last().isBlank()) nested.removeAt(nested.lastIndex)

            out += property(key, value, nested)
        }
        return out
    }

    private fun property(
        key: String,
        value: String,
        nested: List<String>,
    ): FrontMatterProperty =
        when {
            value.startsWith('|') || value.startsWith('>') -> {
                val text = nested.map { it.trim() }
                val joined = if (value.startsWith('|')) text.joinToString("\n") else text.joinToString(" ")
                FrontMatterProperty(key, listOfNotNull(joined.trim().takeIf { it.isNotEmpty() }))
            }

            value.startsWith('[') && value.endsWith(']') ->
                FrontMatterProperty(key, flow(value.substring(1, value.length - 1)), isList = true)

            value.isNotEmpty() -> FrontMatterProperty(key, listOf(unquote(value)))

            nested.any { ITEM.matches(it) } ->
                FrontMatterProperty(
                    key,
                    nested
                        .mapNotNull { ITEM.matchEntire(it)?.groupValues?.get(1) }
                        .map { unquote(it.trim()) }
                        .filter { it.isNotEmpty() },
                    isList = true,
                )

            nested.isNotEmpty() -> FrontMatterProperty(key, nested.map { it.trim() }.filter { it.isNotEmpty() })

            else -> FrontMatterProperty(key, emptyList())
        }

    /** `a, "b, c", d` -- commas inside quotes are not separators. */
    private fun flow(inner: String): List<String> {
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in inner) {
            when {
                quote != null -> {
                    current.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    current.append(ch)
                }
                ch == ',' -> {
                    items += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        items += current.toString()
        return items.map { unquote(it.trim()) }.filter { it.isNotEmpty() }
    }

    private fun unquote(text: String): String =
        if (text.length >= 2 && (text.first() == '"' || text.first() == '\'') && text.last() == text.first()) {
            text.substring(1, text.length - 1)
        } else {
            text
        }

    /**
     * Aliases a note answers to, from `aliases` or `alias`: a list, or one
     * string whose commas separate them.
     */
    fun aliases(properties: List<FrontMatterProperty>): List<String> =
        properties
            .filter { it.key.lowercase() in ALIAS_KEYS }
            .flatMap { property ->
                if (property.isList) property.values else property.values.flatMap { it.split(',') }
            }.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { Slugs.fold(it) }

    private const val FENCE = "---"
    private val ALIAS_KEYS = setOf("aliases", "alias")

    /** A key is anything up to the first colon that is followed by a space or the end. */
    private val KEY = Regex("""([^:]+?):(?:\s+(.*))?$""")
    private val ITEM = Regex("""^\s*-\s*(.*)$""")
}
