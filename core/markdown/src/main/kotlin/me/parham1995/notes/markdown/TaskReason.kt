package me.parham1995.notes.markdown

/**
 * A task's reason: why it was cancelled, or what it is waiting on. Written as
 * a Dataview inline field in the description, after the text and before the
 * Tasks plugin's emoji, so the plugin still reads the line as it always did:
 *
 * ```markdown
 * - [-] Deploy the cluster [reason:: no budget left] ➕ 2025-05-17 ❌ 2025-11-16
 * ```
 *
 * Daftar for Linux and the Mac read and write it by the same rules.
 */
object TaskReason {
    /** A description with its reason taken out. */
    data class Split(
        /** The description without the field, the spaces around it folded into one, trimmed. */
        val text: String,
        /** The field's value, trimmed; null when there is none or it is empty. */
        val reason: String?,
        /** Where the field stood in the description, brackets and all; null when there is none. */
        val range: IntRange? = null,
    )

    /**
     * The first `[reason:: …]` in [description] and the description without
     * it. The value runs to the `]` that balances the field's own `[`, so a
     * `[[wikilink]]` or a `[label](link)` inside it is part of it; a field that
     * never closes is text, and so is any after the first.
     */
    fun split(description: String): Split {
        val match = OPENING.find(description) ?: return Split(description.trim(), null)
        val close = closing(description, match.range.first) ?: return Split(description.trim(), null)
        val value = description.substring(match.range.last + 1, close).trim()
        val text = fold(description.substring(0, match.range.first), description.substring(close + 1))
        return Split(text, value.ifEmpty { null }, match.range.first..close)
    }

    /**
     * [value] as it can be written: trimmed, its line breaks and tabs single
     * spaces, and its square brackets turned round when they do not balance --
     * one stray `]` would close the field early. Null when nothing is left,
     * which is what removes a reason.
     */
    fun clean(value: String?): String? {
        val flat = value.orEmpty().replace(BREAKS, " ").trim()
        if (flat.isEmpty()) return null
        return if (balanced(flat)) flat else flat.replace('[', '(').replace(']', ')')
    }

    /** The field for [value], or null when it says nothing. */
    fun field(value: String?): String? = clean(value)?.let { "[reason:: $it]" }

    /**
     * The same split over a task's first paragraph as the parser built it:
     * the inlines without the field, and its value. A `[[wikilink]]` in the
     * value is its own inline by then, so the brackets are counted in the
     * text around it and the link is written back as it was.
     */
    internal fun extract(inlines: List<MdInline>): Pair<List<MdInline>, String?> {
        for ((start, node) in inlines.withIndex()) {
            val text = (node as? MdInline.Text)?.text ?: continue
            val match = OPENING.find(text) ?: continue
            // The field's own `[` is open; a link inline between is balanced.
            var depth = 1
            val value = StringBuilder()
            for (index in start until inlines.size) {
                val part = inlines[index]
                if (part !is MdInline.Text) {
                    value.append(source(part))
                    continue
                }
                val from = if (index == start) match.range.last + 1 else 0
                for (at in from until part.text.length) {
                    val c = part.text[at]
                    if (c == '[') depth++
                    if (c == ']' && --depth == 0) {
                        val before = inlines.subList(0, start) + MdInline.Text(text.substring(0, match.range.first))
                        val after =
                            listOf(MdInline.Text(part.text.substring(at + 1))) +
                                inlines.subList(index + 1, inlines.size)
                        return joined(before, after) to value.toString().trim().ifEmpty { null }
                    }
                    value.append(c)
                }
            }
            // A field that never closes is text; only the first one counts.
            return inlines to null
        }
        return inlines to null
    }

    /** Two runs of inlines joined with one space where a field was taken out. */
    private fun joined(
        before: List<MdInline>,
        after: List<MdInline>,
    ): List<MdInline> {
        val left = before.trimmed(end = true)
        val right = after.trimmed(end = false)
        val gap = if (left.isNotEmpty() && right.isNotEmpty()) listOf(MdInline.Text(" ")) else emptyList()
        return (left + gap + right).merged()
    }

    private fun List<MdInline>.trimmed(end: Boolean): List<MdInline> {
        val out = filterNot { it is MdInline.Text && it.text.isEmpty() }.toMutableList()
        while (true) {
            val at = if (end) out.lastIndex else 0
            val edge = out.getOrNull(at) as? MdInline.Text ?: return out
            val cut = if (end) edge.text.trimEnd() else edge.text.trimStart()
            if (cut.isNotEmpty()) {
                out[at] = MdInline.Text(cut)
                return out
            }
            out.removeAt(at)
        }
    }

    /** Neighbouring text runs as one, as the parser leaves them. */
    private fun List<MdInline>.merged(): List<MdInline> {
        val out = mutableListOf<MdInline>()
        forEach { node ->
            val last = out.lastOrNull()
            if (node is MdInline.Text && last is MdInline.Text) {
                out[out.lastIndex] = MdInline.Text(last.text + node.text)
            } else {
                out += node
            }
        }
        return out
    }

    /** An inline written back as markdown, near enough to read as the value. */
    private fun source(node: MdInline): String =
        when (node) {
            is MdInline.Text -> node.text
            is MdInline.Code -> "`" + node.code + "`"
            is MdInline.WikiLink ->
                buildString {
                    append("[[").append(node.target)
                    node.heading?.let { append('#').append(it) }
                    node.alias?.let { append('|').append(it) }
                    append("]]")
                }
            is MdInline.Link -> "[" + node.children.joinToString("") { source(it) } + "](" + node.destination + ")"
            is MdInline.Emphasis -> "*" + node.children.joinToString("") { source(it) } + "*"
            is MdInline.Strong -> "**" + node.children.joinToString("") { source(it) } + "**"
            is MdInline.Strikethrough -> "~~" + node.children.joinToString("") { source(it) } + "~~"
            is MdInline.Highlight -> "==" + node.children.joinToString("") { source(it) } + "=="
            is MdInline.Tag -> "#" + node.name
            is MdInline.InlineMath -> "$" + node.latex + "$"
            is MdInline.FootnoteRef -> ""
            MdInline.LineBreak, MdInline.SoftBreak -> " "
        }

    /** The index of the `]` that closes the `[` at [open], or null. */
    private fun closing(
        text: String,
        open: Int,
    ): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> if (--depth == 0) return i
            }
        }
        return null
    }

    private fun balanced(text: String): Boolean {
        var depth = 0
        text.forEach { c ->
            if (c == '[') depth++
            if (c == ']' && --depth < 0) return false
        }
        return depth == 0
    }

    /** Two halves of a description joined where something was taken out of it. */
    internal fun fold(
        before: String,
        after: String,
    ): String =
        when {
            before.isBlank() -> after.trim()
            after.isBlank() -> before.trim()
            else -> before.trimEnd() + " " + after.trimStart()
        }

    /** `[reason::`, any case, with space allowed either side of the name. */
    private val OPENING = Regex("""\[\s*reason\s*::""", RegexOption.IGNORE_CASE)

    private val BREAKS = Regex("""[\r\n\t]+""")
}
