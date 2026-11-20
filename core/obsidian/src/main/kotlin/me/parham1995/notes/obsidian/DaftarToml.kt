package me.parham1995.notes.obsidian

/** One value of a `.daftar.toml`, as far as this app tells them apart. */
sealed interface TomlValue {
    data class Text(
        val value: String,
    ) : TomlValue

    data class Whole(
        val value: Long,
    ) : TomlValue

    data class Flag(
        val value: Boolean,
    ) : TomlValue

    /** An array whose every item is a string. */
    data class Texts(
        val value: List<String>,
    ) : TomlValue

    /** Anything else TOML can hold: a float, a date, an inline table, a mixed array. Never the right type. */
    data class Other(
        val raw: String,
    ) : TomlValue
}

/**
 * Reads the keys of a `.daftar.toml` (or of a `config.toml`, in a test), each
 * under its full dotted name: `text_size` under `[appearance]` is
 * `appearance.text_size`.
 *
 * Not a TOML parser. It reads table headers and `key = value` lines, with
 * strings, whole numbers, `true` and `false`, and arrays of strings that may
 * run over several lines, which is every shape the settings have. Nothing in
 * it can throw: a line it cannot read is left out, and a value it cannot read
 * is [TomlValue.Other], which no setting takes.
 */
object DaftarToml {
    fun parse(text: String): Map<String, TomlValue> {
        val out = LinkedHashMap<String, TomlValue>()
        var table = ""
        val lines = text.lines()
        var at = 0
        while (at < lines.size) {
            var line = uncommented(lines[at]).trim()
            at++
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                // `[[array.of.tables]]` is a table of its own name too.
                table = line.trim('[', ']').split('.').joinToString(".") { unquoted(it) }
                continue
            }
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val key = line.substring(0, equals).split('.').joinToString(".") { unquoted(it) }
            if (key.isEmpty()) continue
            var value = line.substring(equals + 1).trim()
            // An array left open runs on to the line that closes it.
            while (value.startsWith("[") && !closed(value) && at < lines.size) {
                line = uncommented(lines[at]).trim()
                at++
                value = "$value $line"
            }
            out[if (table.isEmpty()) key else "$table.$key"] = valueOf(value)
        }
        return out
    }

    /**
     * [text] with [key] set to [value], and every other line byte for byte:
     * comments, order and blank lines stay. A key that is there has the
     * value of its line changed and keeps the comment after it. One that is
     * not is added at the end of its section, and a section that is not
     * there at the end of the file, with one blank line before it. Null
     * [text] is a vault with no `.daftar.toml` yet.
     */
    fun set(
        text: String?,
        key: String,
        value: TomlValue,
    ): String {
        val source = text.orEmpty()
        val eol = if (source.contains("\r\n")) "\r\n" else "\n"
        val lines = if (source.isEmpty()) mutableListOf() else source.removeSuffix(eol).split(eol).toMutableList()
        val written = written(value)
        val section = key.substringBeforeLast('.', "")
        val name = key.substringAfterLast('.')

        var table = ""
        var last = -1
        var at = 0
        while (at < lines.size) {
            val line = lines[at]
            val bare = uncommented(line).trim()
            val start = at
            at++
            if (bare.isEmpty()) continue
            if (bare.startsWith("[")) {
                table = bare.trim('[', ']').split('.').joinToString(".") { unquoted(it) }
                if (table == section) last = start
                continue
            }
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val path = line.substring(0, equals).split('.').joinToString(".") { unquoted(it) }
            // An array left open runs on to the line that closes it.
            var held = uncommented(line.substring(equals + 1)).trim()
            while (held.startsWith("[") && !closed(held) && at < lines.size) {
                held = "$held " + uncommented(lines[at]).trim()
                at++
            }
            if (table == section) last = at - 1
            if ((if (table.isEmpty()) path else "$table.$path") != key) continue
            // Only the value changes: the key as written, the space after `=`, and the comment stay.
            val after = line.substring(equals + 1)
            val lead = after.takeWhile { it == ' ' || it == '\t' }
            val said = uncommented(after)
            val tail = if (at - 1 == start) after.substring(said.trimEnd().length) else ""
            repeat(at - 1 - start) { lines.removeAt(start + 1) }
            lines[start] = line.substring(0, equals + 1) + lead.ifEmpty { " " } + written + tail
            return lines.joinToString(eol) + eol
        }
        when {
            // A key at the top goes before the first section.
            section.isEmpty() -> {
                val first = lines.indexOfFirst { uncommented(it).trim().startsWith("[") }
                lines.add(if (first < 0) lines.size else first, "$name = $written")
            }
            last >= 0 -> lines.add(last + 1, "$name = $written")
            else -> {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
                lines += "[$section]"
                lines += "$name = $written"
            }
        }
        return lines.joinToString(eol) + eol
    }

    /** [value] as the file writes it. */
    fun written(value: TomlValue): String =
        when (value) {
            is TomlValue.Text -> quoted(value.value)
            is TomlValue.Whole -> value.value.toString()
            is TomlValue.Flag -> value.value.toString()
            is TomlValue.Texts -> value.value.joinToString(", ", "[", "]") { quoted(it) }
            is TomlValue.Other -> value.raw
        }

    private fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun valueOf(raw: String): TomlValue {
        string(raw)?.let { return TomlValue.Text(it) }
        if (raw == "true") return TomlValue.Flag(true)
        if (raw == "false") return TomlValue.Flag(false)
        if (WHOLE.matches(raw)) {
            raw.replace("_", "").toLongOrNull()?.let { return TomlValue.Whole(it) }
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            val items = split(raw.substring(1, raw.length - 1))
            val texts = items.map { string(it) }
            if (texts.all { it != null }) return TomlValue.Texts(texts.filterNotNull())
        }
        return TomlValue.Other(raw)
    }

    /** The text of a whole quoted string, or null when [raw] is not one. */
    private fun string(raw: String): String? {
        if (raw.length < 2) return null
        val quote = raw.first()
        if (quote != '"' && quote != '\'') return null
        val end = closingQuote(raw, 0)
        if (end != raw.length - 1) return null
        val inner = raw.substring(1, end)
        return if (quote == '"') unescaped(inner) else inner
    }

    private fun unescaped(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder()
        var at = 0
        while (at < text.length) {
            val c = text[at]
            if (c == '\\' && at + 1 < text.length) {
                at++
                out.append(
                    when (val next = text[at]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        else -> next
                    },
                )
            } else {
                out.append(c)
            }
            at++
        }
        return out.toString()
    }

    /** Where the string that opens at [from] closes, or -1. */
    private fun closingQuote(
        text: String,
        from: Int,
    ): Int {
        val quote = text[from]
        var at = from + 1
        while (at < text.length) {
            val c = text[at]
            if (c == '\\' && quote == '"') {
                at += 2
                continue
            }
            if (c == quote) return at
            at++
        }
        return -1
    }

    /** [line] without its comment: a `#` inside a string is not one. */
    private fun uncommented(line: String): String {
        var at = 0
        while (at < line.length) {
            val c = line[at]
            if (c == '#') return line.substring(0, at)
            if (c == '"' || c == '\'') {
                val end = closingQuote(line, at)
                if (end < 0) return line
                at = end
            }
            at++
        }
        return line
    }

    /** Whether the array that [value] opens is closed in it. */
    private fun closed(value: String): Boolean {
        var depth = 0
        var at = 0
        while (at < value.length) {
            val c = value[at]
            if (c == '"' || c == '\'') {
                val end = closingQuote(value, at)
                if (end < 0) return false
                at = end
            } else if (c == '[') {
                depth++
            } else if (c == ']') {
                depth--
                if (depth == 0) return true
            }
            at++
        }
        return false
    }

    /** The items of an array's inside, split at the commas that are not in a string. */
    private fun split(inner: String): List<String> {
        val items = mutableListOf<String>()
        var start = 0
        var at = 0
        while (at < inner.length) {
            val c = inner[at]
            if (c == '"' || c == '\'') {
                val end = closingQuote(inner, at)
                if (end < 0) break
                at = end
            } else if (c == ',') {
                items += inner.substring(start, at).trim()
                start = at + 1
            }
            at++
        }
        items += inner.substring(start).trim()
        // A comma after the last item is allowed, and an empty array has no item.
        return items.filter { it.isNotEmpty() }
    }

    private fun unquoted(name: String): String = name.trim().trim('"', '\'').trim()

    private val WHOLE = Regex("""[+-]?\d[\d_]*""")
}
