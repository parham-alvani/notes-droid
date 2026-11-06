package me.parham1995.notes.markdown

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A note's front matter as Obsidian's Properties view shows it: a row for
 * each property, with a type -- `properties.md` in notes-linux's
 * `docs/specs/`, which the Mac and Linux keep too.
 *
 * A property the form did not change prints as it was read, byte for byte; a
 * changed one is written again in its own style, and the body is never
 * touched. YAML the form cannot show without losing something stays text,
 * with the reason.
 *
 * [FrontMatter] is the reader's view of the same lines, forgiving and
 * read-only. This one refuses rather than guesses, because what it reads it
 * may write back.
 */
sealed interface PropertyValue {
    /** `key:` with nothing after it. */
    data object Empty : PropertyValue {
        override val items: List<String> get() = emptyList()
    }

    data class Text(
        val text: String,
    ) : PropertyValue {
        override val items: List<String> get() = listOf(text)
    }

    data class Items(
        override val items: List<String>,
    ) : PropertyValue

    /** The value as a list: none, the one text, or the items. */
    val items: List<String>

    /** An empty text is no value at all: `key:`. */
    fun normal(): PropertyValue = if (this == Text("")) Empty else this
}

/** Obsidian's property types, by the names `.obsidian/types.json` gives them. */
enum class PropertyType(
    val id: String,
) {
    TEXT("text"),
    MULTITEXT("multitext"),
    NUMBER("number"),
    CHECKBOX("checkbox"),
    DATE("date"),
    DATETIME("datetime"),
    ALIASES("aliases"),
    TAGS("tags"),
    ;

    val isList: Boolean get() = this == MULTITEXT || this == ALIASES || this == TAGS

    companion object {
        fun named(name: String): PropertyType? = entries.firstOrNull { it.id == name }

        /**
         * The type Obsidian gives [key]: the one [types] (`types.json`) names,
         * its key matched without case; else that of `tags`, `aliases` and
         * `cssclasses`; else the shape of [value].
         */
        fun infer(
            key: String,
            value: PropertyValue,
            types: Map<String, String> = emptyMap(),
        ): PropertyType {
            val named = types[key] ?: types.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            named?.let(::named)?.let { return it }
            when (key.lowercase()) {
                "tags", "tag" -> return TAGS
                "aliases", "alias" -> return ALIASES
                "cssclasses", "cssclass" -> return MULTITEXT
            }
            return when (value) {
                PropertyValue.Empty -> TEXT
                is PropertyValue.Items -> MULTITEXT
                is PropertyValue.Text -> {
                    val text = value.text
                    when {
                        text.lowercase() == "true" || text.lowercase() == "false" -> CHECKBOX
                        NUMERAL.matches(text) -> NUMBER
                        DAY.matches(text) && date(text) != null -> DATE
                        MOMENT.matches(text) && date(text) != null -> DATETIME
                        else -> TEXT
                    }
                }
            }
        }

        /** `2026-10-25`, or with a time: `2026-10-25T09:30`, a space for the `T`, seconds. */
        fun date(text: String): LocalDateTime? {
            val trimmed = text.trim().removeSuffix("Z")
            if (DAY.matches(trimmed)) return runCatching { LocalDate.parse(trimmed).atStartOfDay() }.getOrNull()
            if (!MOMENT.matches(text.trim())) return null
            val day = runCatching { LocalDate.parse(trimmed.take(DAY_LENGTH)) }.getOrNull() ?: return null
            val time = runCatching { LocalTime.parse(trimmed.drop(DAY_LENGTH + 1)) }.getOrNull() ?: return null
            return day.atTime(time)
        }

        /** As Obsidian writes a date (`2026-10-25`) or a date and time (`2026-10-25T09:30`). */
        fun format(
            date: LocalDateTime,
            time: Boolean,
        ): String {
            val day = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
            return if (time) day + "T%02d:%02d".format(date.hour, date.minute) else day
        }

        private const val DAY_LENGTH = 10
        private val NUMERAL = Regex("""^-?(\d+(\.\d*)?|\.\d+)([eE][-+]?\d+)?$""")
        private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private val MOMENT = Regex("""^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?Z?$""")
    }
}

/** What the note screen shows for a note's front matter. */
sealed interface FrontMatterForm {
    /** The note has no front matter; a property added makes one. */
    data class Absent(
        override val sheet: PropertySheet,
    ) : FrontMatterForm

    data class Form(
        override val sheet: PropertySheet,
    ) : FrontMatterForm

    /** YAML the form cannot show without losing something, and why. */
    data class Source(
        val reason: String,
    ) : FrontMatterForm {
        override val sheet: PropertySheet? get() = null
    }

    /** The form to edit; null for [Source]. */
    val sheet: PropertySheet?
}

enum class YamlQuote { PLAIN, SINGLE, DOUBLE }

/** A name the form will not write, and why. */
class PropertyFailure(
    message: String,
) : Exception(message) {
    companion object {
        fun exists(key: String) = PropertyFailure("The note has a property “$key” already.")

        fun badName() = PropertyFailure("A property needs a name.")
    }
}

/**
 * One property of a [PropertySheet]: its [key] and [value], and how it was
 * written, so that what did not change prints as it was.
 */
data class Property(
    val key: String,
    val value: PropertyValue,
    /** The key as written, quotes and all. */
    internal val keyText: String,
    /** The lines as written; null once the value changed. */
    internal val lines: List<String>?,
    /** Blank lines after it, kept where they are. */
    internal val trailing: List<String> = emptyList(),
    internal val style: Style = Style(),
) {
    data class Style(
        /** Of the scalar, or of a list's first item. */
        val quote: YamlQuote = YamlQuote.PLAIN,
        /** `[a, b]`, not a block of `- a` lines. */
        val flow: Boolean = false,
        /** What goes before a block item's value: `  - `. */
        val itemPrefix: String = "  - ",
        /** Each item's value and its text as written, to reuse for one that did not change. */
        val items: List<Pair<String, String>> = emptyList(),
    )
}

/** YAML the form cannot show, and why. */
private class Unrepresentable(
    val reason: String,
) : Exception(reason)

/**
 * The front matter as a list of properties, each with the lines it was
 * written in. Changed by [set], [add], [remove] and [rename]; [applied] puts
 * it back on a note, only one whose front matter is still [original].
 */
class PropertySheet private constructor(
    /** The front matter as read, fences and the newline after them included. */
    val original: String,
    private val open: String = FENCE,
    private val close: String = FENCE,
    private val closeNewline: Boolean = true,
    private val eol: String = "",
) {
    private val list = mutableListOf<Property>()
    private val leading = mutableListOf<String>()
    private var edited = false

    val properties: List<Property> get() = list.toList()

    fun value(key: String): PropertyValue? = list.firstOrNull { it.key == key }?.value

    /** A new value for [key]; nothing when it has that value already. */
    fun set(
        key: String,
        value: PropertyValue,
    ) {
        val normal = value.normal()
        val at = list.indexOfFirst { it.key == key }
        if (at < 0 || list[at].value == normal) return
        list[at] = list[at].copy(value = normal, lines = null)
        edited = true
    }

    /** A new property, after the others. */
    fun add(
        key: String,
        value: PropertyValue,
    ) {
        val name = key.trim(' ', '\t')
        if (name.isEmpty() || name.contains('\n') || name.contains('\r')) throw PropertyFailure.badName()
        if (list.any { it.key == name }) throw PropertyFailure.exists(name)
        list += Property(name, value.normal(), keyText(name), lines = null)
        edited = true
    }

    /** Takes [key] out; its blank lines stay with the property before it. */
    fun remove(key: String) {
        val at = list.indexOfFirst { it.key == key }
        if (at < 0) return
        val gone = list.removeAt(at)
        if (at > 0) {
            list[at - 1] = list[at - 1].let { it.copy(trailing = it.trailing + gone.trailing) }
        } else {
            leading += gone.trailing
        }
        edited = true
    }

    /** Renames [key] in place; its value and its lines stay as they are. */
    fun rename(
        key: String,
        new: String,
    ) {
        val name = new.trim(' ', '\t')
        if (name.isEmpty() || name.contains('\n') || name.contains('\r')) throw PropertyFailure.badName()
        val at = list.indexOfFirst { it.key == key }
        if (at < 0 || name == key) return
        if (list.any { it.key == name }) throw PropertyFailure.exists(name)
        val text = keyText(name)
        val property = list[at]
        val written = property.lines
        val first = written?.firstOrNull()
        val lines =
            if (written != null && first != null && first.startsWith(property.keyText)) {
                listOf(text + first.substring(property.keyText.length)) + written.drop(1)
            } else {
                null
            }
        list[at] = property.copy(key = name, keyText = text, lines = lines)
        edited = true
    }

    /**
     * The front matter as it prints now: [original] when nothing changed,
     * nothing when the last property went.
     */
    fun text(): String {
        if (!edited) return original
        if (list.isEmpty()) return ""
        val lines = mutableListOf(open)
        lines += leading
        list.forEach { property ->
            lines += property.lines ?: linesOf(property).map { it + eol }
            lines += property.trailing
        }
        lines += close
        return lines.joinToString("\n") + if (closeNewline) "\n" else ""
    }

    /**
     * [note] with this front matter, or null when the note's front matter is
     * no longer the one this sheet was read from.
     */
    fun applied(note: String): String? {
        val now = FrontMatterBlock.of(note)?.text.orEmpty()
        if (now != original) return null
        return text() + note.substring(now.length)
    }

    companion object {
        /** The front matter of [note], as a form when every line of it fits one. */
        fun read(note: String): FrontMatterForm {
            val block =
                FrontMatterBlock.of(note)
                    ?: return FrontMatterForm.Absent(
                        if (note.contains("\r\n")) {
                            PropertySheet("", open = "$FENCE\r", close = "$FENCE\r", eol = "\r")
                        } else {
                            PropertySheet("")
                        },
                    )
            return try {
                FrontMatterForm.Form(fromBlock(block))
            } catch (raw: Unrepresentable) {
                FrontMatterForm.Source(raw.reason)
            }
        }

        /**
         * Where the front matter ends, in UTF-16 units as Android's text
         * counts them, as the Mac's text view does: the fences and the
         * newline after the closing one. 0 with none.
         */
        fun length(note: String): Int = FrontMatterBlock.of(note)?.text?.length ?: 0

        /**
         * [note] with [edit] made to its front matter, read from [note] as it
         * is now, or a [PropertyFailure] that says why not: front matter the
         * form cannot show, a name that is taken, or front matter that is not
         * [expected] any more -- the one the form was read from. Null
         * [expected] skips that check.
         */
        fun edit(
            note: String,
            edit: PropertyEdit,
            expected: String? = null,
        ): String {
            val form = read(note)
            val sheet = form.sheet ?: throw PropertyFailure((form as FrontMatterForm.Source).reason)
            if (expected != null && sheet.original != expected) throw PropertyFailure(CHANGED)
            when (edit) {
                is PropertyEdit.Set -> sheet.set(edit.key, edit.value)
                is PropertyEdit.Add -> sheet.add(edit.key, edit.value)
                is PropertyEdit.Remove -> sheet.remove(edit.key)
                is PropertyEdit.Rename -> sheet.rename(edit.key, edit.to)
            }
            return sheet.applied(note) ?: throw PropertyFailure(CHANGED)
        }

        /** What a refusal over changed front matter says. */
        const val CHANGED = "The front matter changed meanwhile."

        private fun fromBlock(block: FrontMatterBlock): PropertySheet {
            val sheet =
                PropertySheet(
                    original = block.text,
                    open = block.open,
                    close = block.close,
                    closeNewline = block.closeNewline,
                    eol = if (block.open.endsWith('\r')) "\r" else "",
                )
            val lines = block.lines
            var at = 0
            while (at < lines.size && isBlank(lines[at])) sheet.leading += lines[at++]
            while (at < lines.size) {
                val line = lines[at]
                if (isBlank(line)) {
                    sheet.list.lastOrNull()?.let { last ->
                        sheet.list[sheet.list.lastIndex] = last.copy(trailing = last.trailing + line)
                    }
                    at++
                    continue
                }
                val text = content(line)
                if (text.startsWith(' ') || text.startsWith('\t')) {
                    raw(
                        if (splitKey(text.trim(' ', '\t')) != null) {
                            "It has a map inside a map."
                        } else {
                            "A value goes on over more than one line."
                        },
                    )
                }
                if (text.startsWith('#')) raw("It has a comment.")
                if (text.startsWith("- ") || text == "-") raw("It is a list, not a list of properties.")
                val (keyText, key, rest) = splitKey(text) ?: raw("The line “$text” is not a property.")
                if (sheet.list.any { it.key == key }) raw("The property “$key” is there twice.")
                val written = mutableListOf(line)
                var value: PropertyValue = PropertyValue.Empty
                var style = Property.Style()
                at++
                when {
                    rest.isEmpty() -> {
                        // A block list, under the key or at the key's own indent.
                        val items = mutableListOf<Pair<String, String>>()
                        var prefix: String? = null
                        while (at < lines.size && !isBlank(lines[at])) {
                            val item = content(lines[at])
                            val indent = item.takeWhile { it == ' ' }
                            val after = item.substring(indent.length)
                            if (!(after.startsWith("- ") || after == "-")) {
                                if (indent.isEmpty()) break
                                raw(
                                    if (splitKey(after) != null) {
                                        "“$key” holds a map inside a map."
                                    } else {
                                        "“$key” goes on over more than one line."
                                    },
                                )
                            }
                            val spaces = after.substring(1).takeWhile { it == ' ' }
                            if (prefix != null && prefix.takeWhile { it == ' ' }.length != indent.length) {
                                raw("The list “$key” has items at different indents.")
                            }
                            if (prefix == null) prefix = if (spaces.isEmpty()) "$indent- " else "$indent-$spaces"
                            val itemText = after.substring(1 + spaces.length)
                            if (itemText.startsWith("- ") || itemText == "-") raw("“$key” holds a list inside a list.")
                            if (splitKey(itemText) != null) raw("“$key” holds a map inside a list.")
                            val (parsed, quote) = scalar(itemText, key)
                            if (items.isEmpty()) style = style.copy(quote = quote)
                            items += parsed to itemText
                            written += lines[at]
                            at++
                        }
                        // The item's own value may go on, more indented, on the next line.
                        if (at < lines.size && !isBlank(lines[at]) && content(lines[at]).startsWith(' ')) {
                            raw("An item of “$key” goes on over more than one line.")
                        }
                        if (prefix != null) {
                            value = PropertyValue.Items(items.map { it.first })
                            style = style.copy(itemPrefix = prefix, items = items)
                        }
                    }

                    rest.startsWith('[') -> {
                        if (!rest.endsWith(']')) {
                            raw(
                                if (rest.contains(
                                        " #",
                                    )
                                ) {
                                    "It has a comment."
                                } else {
                                    "The list “$key” does not close on its line."
                                },
                            )
                        }
                        val inner = rest.substring(1, rest.length - 1).trim(' ', '\t')
                        val items = mutableListOf<Pair<String, String>>()
                        (if (inner.isEmpty()) emptyList() else splitFlow(inner)).forEach { itemText ->
                            if (itemText.startsWith('[') || itemText.startsWith('{')) {
                                raw("“$key” holds a list inside a list; a link there needs quotes.")
                            }
                            val (parsed, quote) = scalar(itemText, key)
                            if (items.isEmpty()) style = style.copy(quote = quote)
                            items += parsed to itemText
                        }
                        value = PropertyValue.Items(items.map { it.first })
                        style = style.copy(flow = true, items = items)
                    }

                    else -> {
                        val (parsed, quote) = scalar(rest, key)
                        value = PropertyValue.Text(parsed)
                        style = style.copy(quote = quote)
                    }
                }
                sheet.list += Property(key, value, keyText, written, style = style)
            }
            return sheet
        }

        private fun raw(reason: String): Nothing = throw Unrepresentable(reason)

        private fun content(line: String): String = line.removeSuffix("\r")

        private fun isBlank(line: String): Boolean = line.all { it == ' ' || it == '\t' || it == '\r' }

        /** `key: value`: the key as written, the key, and the rest of the line. */
        internal fun splitKey(text: String): Triple<String, String, String>? {
            val first = text.firstOrNull() ?: return null
            if (first == '"' || first == '\'') {
                val close = text.indexOf(first, 1).takeIf { it > 0 } ?: return null
                val name = text.substring(1, close)
                val after = text.substring(close + 1)
                if (!after.startsWith(':') ||
                    !(after.length == 1 || after[1] == ' ') ||
                    name.contains('\\')
                ) {
                    return null
                }
                return Triple(text.substring(0, close + 1), name, after.substring(1).trim(' ', '\t'))
            }
            if (first in INDICATORS) return null
            var from = 0
            while (true) {
                val colon = text.indexOf(':', from).takeIf { it >= 0 } ?: return null
                val next = text.substring(colon + 1)
                if (next.isEmpty() || next.startsWith(' ') || next.startsWith('\t')) {
                    val key = text.substring(0, colon)
                    if (key != key.trim(' ', '\t') || key.isEmpty()) return null
                    return Triple(key, key, next.trim(' ', '\t'))
                }
                from = colon + 1
            }
        }

        /** One scalar on one line, and how it was quoted. */
        internal fun scalar(
            text: String,
            key: String,
        ): Pair<String, YamlQuote> {
            val first = text.firstOrNull() ?: return "" to YamlQuote.PLAIN
            val open = {
                Unrepresentable(
                    if (text.contains(" #")) "It has a comment." else "A quote in “$key” does not close on its line.",
                )
            }
            return when (first) {
                '\'' -> {
                    if (text.length < 2 || !text.endsWith('\'')) throw open()
                    val inner = text.substring(1, text.length - 1)
                    // A lone quote inside ends the string early: what follows is not part of it.
                    if (inner.replace("''", "").contains('\'')) throw open()
                    inner.replace("''", "'") to YamlQuote.SINGLE
                }

                '"' -> {
                    if (text.length < 2 || !text.endsWith('"')) throw open()
                    val out = StringBuilder()
                    var escaped = false
                    for (c in text.substring(1, text.length - 1)) {
                        when {
                            escaped -> {
                                when (c) {
                                    '\\', '"', '/' -> out.append(c)
                                    'n' -> out.append('\n')
                                    'r' -> out.append('\r')
                                    't' -> out.append('\t')
                                    else -> raw("“$key” has an escape the form does not know.")
                                }
                                escaped = false
                            }
                            c == '\\' -> escaped = true
                            c == '"' -> throw open()
                            else -> out.append(c)
                        }
                    }
                    if (escaped) throw open()
                    out.toString() to YamlQuote.DOUBLE
                }

                '&', '*' -> raw("It has an anchor or an alias (“$key”).")
                '!' -> raw("“$key” has a YAML tag.")
                '|', '>' -> raw("“$key” is text on more than one line.")
                '{' -> raw("“$key” holds a map inside a map.")
                '%', '@', '`' -> raw("“$key” starts with a character YAML keeps for itself.")
                else -> {
                    if (text.contains(" #") || text.contains("\t#")) raw("It has a comment.")
                    if (text.contains(": ") || text.endsWith(':')) raw("“$key” has a colon that YAML reads as a map.")
                    text.trim(' ', '\t') to YamlQuote.PLAIN
                }
            }
        }

        /** The items of a flow list, split at the commas outside quotes and brackets. */
        private fun splitFlow(text: String): List<String> {
            val items = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var quote: Char? = null
            var escaped = false
            for (c in text) {
                if (quote != null) {
                    // `\"` does not close a double-quoted item.
                    when {
                        escaped -> escaped = false
                        quote == '"' && c == '\\' -> escaped = true
                        c == quote -> quote = null
                    }
                } else if ((c == '"' || c == '\'') && current.all { it == ' ' }) {
                    // Only at an item's start: `it's` is plain text.
                    quote = c
                } else if (c == '[' || c == '{') {
                    depth++
                } else if (c == ']' || c == '}') {
                    depth--
                } else if (c == ',' && depth == 0) {
                    items += current.toString().trim(' ', '\t')
                    current.clear()
                    continue
                }
                current.append(c)
            }
            items += current.toString().trim(' ', '\t')
            return items
        }

        private fun linesOf(property: Property): List<String> {
            val key = "${property.keyText}:"
            val style = property.style
            return when (val value = property.value) {
                PropertyValue.Empty -> listOf(key)
                is PropertyValue.Text ->
                    if (value.text.isEmpty()) {
                        listOf(
                            key,
                        )
                    } else {
                        listOf("$key ${quoted(value.text, style.quote)}")
                    }
                is PropertyValue.Items -> {
                    val unused = style.items.toMutableList()
                    val texts =
                        value.items.map { item ->
                            val at = unused.indexOfFirst { it.first == item }
                            if (at >= 0) unused.removeAt(at).second else quoted(item, style.quote, style.flow)
                        }
                    when {
                        style.flow -> listOf("$key [${texts.joinToString(", ")}]")
                        texts.isEmpty() -> listOf(key)
                        else -> listOf(key) + texts.map { style.itemPrefix + it }
                    }
                }
            }
        }

        /** A key as YAML reads it back: plain, or in quotes that hold no escape. */
        private fun keyText(name: String): String {
            val first = name.first()
            if (first !in INDICATORS &&
                !name.contains(": ") &&
                !name.endsWith(':') &&
                !name.contains(" #") &&
                name.none { it == '\n' || it == '\r' || it == '\t' }
            ) {
                return name
            }
            if (!name.contains('"') && !name.contains('\\')) return "\"$name\""
            if (!name.contains('\'')) return "'$name'"
            throw PropertyFailure.badName()
        }

        /** [text] in the style asked for, or in double quotes when plain text would read as something else. */
        internal fun quoted(
            text: String,
            quote: YamlQuote,
            flow: Boolean = false,
        ): String =
            when {
                quote == YamlQuote.SINGLE && text.none { it == '\n' || it == '\r' || it == '\t' } ->
                    "'" + text.replace("'", "''") + "'"
                quote == YamlQuote.PLAIN && isPlain(text, flow) -> text
                else -> "\"" + escape(text) + "\""
            }

        private fun escape(text: String): String =
            text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")

        private fun isPlain(
            text: String,
            flow: Boolean,
        ): Boolean {
            val first = text.firstOrNull() ?: return false
            val last = text.last()
            return when {
                first == ' ' || last == ' ' -> false
                first in INDICATORS && !(first == '-' && text.length > 1 && text[1] != ' ') -> false
                text in NULLS -> false
                text.contains(": ") || text.endsWith(':') || text.contains(" #") -> false
                text.any { it.code < SPACE } -> false
                flow && text.any { it in ",[]{}" } -> false
                else -> true
            }
        }

        private const val FENCE = "---"
        private const val SPACE = 32
        private val NULLS = setOf("null", "Null", "NULL", "~")
        private val INDICATORS = "-?:,[]{}#&*!|>'\"%@`".toSet()
    }
}

/** One change the form makes, as it is queued: [encode] is one string. */
sealed interface PropertyEdit {
    data class Set(
        val key: String,
        val value: PropertyValue,
    ) : PropertyEdit

    data class Add(
        val key: String,
        val value: PropertyValue,
    ) : PropertyEdit

    data class Remove(
        val key: String,
    ) : PropertyEdit

    data class Rename(
        val key: String,
        val to: String,
    ) : PropertyEdit

    /** The edit as one string: the operation, the key, and the value. */
    fun encode(): String {
        fun value(value: PropertyValue) =
            when (value) {
                PropertyValue.Empty -> "empty"
                is PropertyValue.Text -> "text$PART${value.text}"
                is PropertyValue.Items -> "list$PART${value.items.joinToString(ITEM.toString())}"
            }
        return when (this) {
            is Set -> "set$PART$key$PART${value(value)}"
            is Add -> "add$PART$key$PART${value(value)}"
            is Remove -> "remove$PART$key"
            is Rename -> "rename$PART$key$PART$to"
        }
    }

    companion object {
        /** Between the parts of an encoded edit, and between a list's items: no property and no value holds them. */
        private const val PART = '\u001e'
        private const val ITEM = '\u001f'

        fun decode(text: String): PropertyEdit? {
            val parts = text.split(PART, limit = 4)
            val key = parts.getOrNull(1) ?: return null
            val value: PropertyValue? =
                when {
                    parts.size == 3 && parts[2] == "empty" -> PropertyValue.Empty
                    parts.size == 4 && parts[2] == "text" -> PropertyValue.Text(parts[3])
                    parts.size == 4 && parts[2] == "list" ->
                        PropertyValue.Items(if (parts[3].isEmpty()) emptyList() else parts[3].split(ITEM))
                    else -> null
                }
            return when (parts[0]) {
                "set" -> value?.let { Set(key, it) }
                "add" -> value?.let { Add(key, it) }
                "remove" -> if (parts.size == 2) Remove(key) else null
                "rename" -> if (parts.size == 3) Rename(key, parts[2]) else null
                else -> null
            }
        }
    }
}

/**
 * The front matter's fences and lines in a note, as written: a first line of
 * `---` and the next `---` or `...` line.
 */
private class FrontMatterBlock(
    /** From the start through the closing fence and its newline. */
    val text: String,
    val open: String,
    val close: String,
    val closeNewline: Boolean,
    /** The lines between the fences, each with its `\r` if it had one. */
    val lines: List<String>,
) {
    companion object {
        fun of(note: String): FrontMatterBlock? {
            val lines = mutableListOf<String>()
            var opening = ""
            var start = 0
            var first = true
            while (start < note.length) {
                val end = note.indexOf('\n', start).let { if (it < 0) note.length else it }
                val line = note.substring(start, end)
                val bare = line.removeSuffix("\r").trim(' ', '\t')
                if (first) {
                    if (bare != "---") return null
                    opening = line
                    first = false
                } else if (bare == "---" || bare == "...") {
                    val closeNewline = end < note.length
                    return FrontMatterBlock(
                        text = note.substring(0, if (closeNewline) end + 1 else end),
                        open = opening,
                        close = line,
                        closeNewline = closeNewline,
                        lines = lines,
                    )
                } else {
                    lines += line
                }
                if (end >= note.length) break
                start = end + 1
            }
            return null
        }
    }
}

/** The property names a vault uses, the most used first, for completing a new one's name. */
object PropertyNames {
    fun ranked(keys: Iterable<String>): List<String> =
        keys
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }

    /** The names that start with [prefix], then those that hold it. */
    fun complete(
        prefix: String,
        names: List<String>,
        limit: Int,
    ): List<String> {
        val query = prefix.trim(' ', '\t').lowercase()
        if (query.isEmpty()) return names.take(limit)
        val starts = names.filter { it.lowercase().startsWith(query) && it.lowercase() != query }
        val holds = names.filter { !it.lowercase().startsWith(query) && it.lowercase().contains(query) }
        return (starts + holds).take(limit)
    }
}
