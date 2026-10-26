package me.parham1995.notes.markdown

/**
 * A YAML document as a `.base` file writes it: block maps and lists, plain and
 * quoted scalars, flow lists and block scalars. Not anchors, tags or several
 * documents in one file; Obsidian writes none of those.
 *
 * [FrontMatter] reads a note's properties and stays as it is: it knows the
 * shapes a person types into front matter, and a base is nested several maps
 * deep, which it does not try to read.
 */
sealed interface YamlNode {
    data object Null : YamlNode

    data class Scalar(
        val text: String,
    ) : YamlNode

    data class Sequence(
        val values: List<YamlNode>,
    ) : YamlNode

    /** In the file's order. */
    data class Mapping(
        val pairs: List<Pair<String, YamlNode>>,
    ) : YamlNode

    operator fun get(key: String): YamlNode? = (this as? Mapping)?.pairs?.firstOrNull { it.first == key }?.second

    val string: String? get() = (this as? Scalar)?.text

    /** A list's items; a single value is a list of one, and nothing is none. */
    val items: List<YamlNode>
        get() =
            when (this) {
                is Sequence -> values
                Null -> emptyList()
                else -> listOf(this)
            }

    val entries: List<Pair<String, YamlNode>> get() = (this as? Mapping)?.pairs.orEmpty()
}

object Yaml {
    private class Line(
        val indent: Int,
        val text: String,
    )

    fun parse(source: String): YamlNode {
        val lines =
            source
                .split('\n')
                .mapNotNull { raw ->
                    val line = raw.removeSuffix("\r")
                    val text = line.trim()
                    if (text.isEmpty() || text.startsWith("#") || text == "---" || text == "...") {
                        null
                    } else {
                        Line(line.takeWhile { it == ' ' }.length, text)
                    }
                }.toMutableList()
        val first = lines.firstOrNull() ?: return YamlNode.Null
        return Reader(lines).block(first.indent)
    }

    private fun isItem(text: String): Boolean = text == "-" || text.startsWith("- ")

    private class Reader(
        val lines: MutableList<Line>,
    ) {
        var at = 0

        fun block(indent: Int): YamlNode {
            if (at >= lines.size) return YamlNode.Null
            return if (isItem(lines[at].text)) list(indent) else map(indent)
        }

        private fun list(indent: Int): YamlNode {
            val items = mutableListOf<YamlNode>()
            while (at < lines.size && lines[at].indent == indent && isItem(lines[at].text)) {
                val text = lines[at].text
                val rest = text.drop(1).trim()
                when {
                    rest.isEmpty() -> {
                        at++
                        items +=
                            if (at < lines.size && lines[at].indent > indent) block(lines[at].indent) else YamlNode.Null
                    }

                    splitKey(rest) != null || isItem(rest) -> {
                        // `- key: value` starts a map whose keys line up after the dash.
                        val inner = indent + text.length - rest.length
                        lines[at] = Line(inner, rest)
                        items += block(inner)
                    }

                    else -> items += scalar(rest, indent)
                }
            }
            return YamlNode.Sequence(items)
        }

        private fun map(indent: Int): YamlNode {
            val entries = mutableListOf<Pair<String, YamlNode>>()
            while (at < lines.size && lines[at].indent == indent && !isItem(lines[at].text)) {
                val split = splitKey(lines[at].text)
                if (split == null) {
                    at++
                    continue
                }
                val (key, value) = split
                if (value.isEmpty()) {
                    at++
                    // A list under a key may sit at the key's own indent.
                    val nested =
                        at < lines.size &&
                            (lines[at].indent > indent || (lines[at].indent == indent && isItem(lines[at].text)))
                    entries += key to (if (nested) block(lines[at].indent) else YamlNode.Null)
                } else {
                    entries += key to scalar(value, indent)
                }
            }
            return YamlNode.Mapping(entries)
        }

        private fun scalar(
            value: String,
            parent: Int,
        ): YamlNode {
            at++
            val style = value.first()
            if ((style == '|' || style == '>') && value.length <= 2) {
                val parts = mutableListOf<String>()
                while (at < lines.size && lines[at].indent > parent) {
                    parts += lines[at].text
                    at++
                }
                return YamlNode.Scalar(parts.joinToString(if (style == '|') "\n" else " "))
            }
            return inline(value)
        }
    }

    /**
     * `key: value`, where the key is plain or quoted; an expression with a
     * colon in a string is not a key.
     */
    internal fun splitKey(text: String): Pair<String, String>? {
        val quote = text.first()
        if (quote == '"' || quote == '\'') {
            val close = text.indexOf(quote, 1)
            if (close < 0) return null
            val after = text.substring(close + 1)
            if (!after.startsWith(":") || (after.length > 1 && after[1] != ' ')) return null
            return text.substring(1, close) to after.drop(1).trim()
        }
        var from = 0
        while (true) {
            val colon = text.indexOf(':', from)
            if (colon < 0) return null
            if (colon + 1 == text.length || text[colon + 1] == ' ') {
                val key = text.substring(0, colon)
                if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it in KEY_PUNCTUATION }) return null
                return key.trim() to text.substring(colon + 1).trim()
            }
            from = colon + 1
        }
    }

    /** One value on one line: quoted, a flow list, or plain. */
    internal fun inline(value: String): YamlNode {
        val text = stripComment(value)
        return when {
            text == "~" || text == "null" || text.isEmpty() -> YamlNode.Null
            text == "{}" -> YamlNode.Mapping(emptyList())
            text.startsWith("[") && text.endsWith("]") -> {
                val inner = text.substring(1, text.length - 1).trim()
                YamlNode.Sequence(if (inner.isEmpty()) emptyList() else splitFlow(inner).map(::inline))
            }
            text.length >= 2 && text.startsWith("'") && text.endsWith("'") ->
                YamlNode.Scalar(text.substring(1, text.length - 1).replace("''", "'"))
            text.length >= 2 && text.startsWith("\"") && text.endsWith("\"") -> YamlNode.Scalar(unescape(text))
            else -> YamlNode.Scalar(text)
        }
    }

    private fun unescape(text: String): String =
        buildString {
            var escaped = false
            for (ch in text.substring(1, text.length - 1)) {
                when {
                    escaped -> {
                        append(
                            when (ch) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> ch
                            },
                        )
                        escaped = false
                    }
                    ch == '\\' -> escaped = true
                    else -> append(ch)
                }
            }
        }

    /** A ` #` outside quotes starts a comment. */
    private fun stripComment(text: String): String {
        if (!text.contains(" #")) return text
        var quote: Char? = null
        var previous = ' '
        text.forEachIndexed { offset, ch ->
            if (quote != null) {
                if (ch == quote) quote = null
            } else if (ch == '"' || ch == '\'') {
                quote = ch
            } else if (ch == '#' && previous == ' ') {
                return text.substring(0, offset).trim()
            }
            previous = ch
        }
        return text
    }

    /** The items of a flow list, split at the commas outside quotes and brackets. */
    private fun splitFlow(text: String): List<String> {
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (ch in text) {
            if (quote != null) {
                if (ch == quote) quote = null
            } else if (ch == '"' || ch == '\'') {
                quote = ch
            } else if (ch == '[' || ch == '{') {
                depth++
            } else if (ch == ']' || ch == '}') {
                depth--
            } else if (ch == ',' && depth == 0) {
                items += current.toString().trim()
                current.clear()
                continue
            }
            current.append(ch)
        }
        items += current.toString().trim()
        return items
    }

    private const val KEY_PUNCTUATION = "_-. $/"
}
