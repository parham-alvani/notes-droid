package me.parham1995.notes.markdown

/**
 * The first few lines of a note as plain, readable text.
 *
 * What a pinned note's card on the home screen shows. A widget has one text
 * view per card and no renderer behind it, so the markup has to go before the
 * text gets there: `## Plans` is read as "Plans", a task as a box with its
 * words beside it, a link as the words it was written with. Showing the source
 * instead would put hashes, brackets and `- [ ]` in front of every line, and a
 * card is small enough that they would be most of what it says.
 *
 * Built from the parsed note rather than from the source with a handful of
 * regular expressions, so what the card says agrees with what the reader
 * draws -- a wikilink's alias, a callout's title, front matter left out --
 * without writing the parser a second time.
 *
 * One entry per line, blank lines dropped. Each line keeps its own words in
 * their own order; a Persian line and an English one sit side by side and the
 * text view gives each its own direction from its first strong character.
 */
object NoteExcerpt {
    /** Lines a card shows. */
    const val DEFAULT_LINES = 8

    /**
     * The longest a single line may be before it is cut.
     *
     * A card wraps and clips on its own; this is only here so one enormous
     * paragraph cannot carry the whole note into the widget.
     */
    const val MAX_LINE_CHARS = 240

    /**
     * The first [maxLines] lines of [markdown], as plain text.
     *
     * A heading at the very top that only repeats [title] is left out: the
     * card already says the title, and saying it twice spends one of eight
     * lines on nothing.
     */
    fun lines(
        markdown: String,
        maxLines: Int = DEFAULT_LINES,
        title: String? = null,
    ): List<String> {
        if (maxLines <= 0) return emptyList()
        val blocks = MarkdownParser.parseNote(markdown).blocks
        val out = Collector(maxLines)
        var first = true
        for (block in blocks) {
            if (out.full) break
            if (first && block is MdBlock.Heading && title != null && block.sameAs(title)) {
                first = false
                continue
            }
            if (block !is MdBlock.FrontMatter) first = false
            out.block(block, indent = "")
        }
        return out.lines
    }

    /** [lines], one per line, for a single text view. */
    fun text(
        markdown: String,
        maxLines: Int = DEFAULT_LINES,
        title: String? = null,
    ): String = lines(markdown, maxLines, title).joinToString("\n")

    private fun MdBlock.Heading.sameAs(title: String): Boolean =
        plainText(inlines).trim().equals(title.trim(), ignoreCase = true)

    private class Collector(
        val max: Int,
    ) {
        val lines = ArrayList<String>()

        val full: Boolean get() = lines.size >= max

        fun add(
            indent: String,
            text: String,
        ) {
            if (full) return
            val clean = text.trim()
            if (clean.isEmpty()) return
            lines.add(indent + clip(clean))
        }

        /** Each line of a run of inlines, split where the note breaks it. */
        fun inlines(
            indent: String,
            inlines: List<MdInline>,
            prefix: String = "",
        ) {
            var lead = prefix
            splitAtBreaks(inlines).forEach { segment ->
                val text = plainText(segment).trim()
                if (text.isEmpty()) return@forEach
                add(indent, lead + text)
                // The marker belongs to the first line only; the rest of the
                // item lines up under the words, not under the bullet.
                if (lead.isNotEmpty()) lead = ""
            }
        }

        fun block(
            block: MdBlock,
            indent: String,
        ) {
            if (full) return
            when (block) {
                is MdBlock.Heading -> add(indent, plainText(block.inlines))
                is MdBlock.Paragraph -> inlines(indent, block.inlines)
                is MdBlock.ListBlock -> list(block, indent)
                is MdBlock.Quote -> block.children.forEach { block(it, indent) }
                is MdBlock.Callout -> {
                    val title = plainText(block.title).trim()
                    add(indent, title.ifEmpty { block.type.replaceFirstChar { it.uppercaseChar() } })
                    // Folded shut in the note, so shut here as well.
                    if (!block.collapsed) block.children.forEach { block(it, indent) }
                }
                // The code, without the fences around it.
                is MdBlock.CodeBlock -> block.code.lineSequence().forEach { add(indent, it) }
                is MdBlock.Table -> {
                    add(indent, block.header.joinToString(" · ") { plainText(it).trim() })
                    block.rows.forEach { row -> add(indent, row.joinToString(" · ") { plainText(it).trim() }) }
                }
                // Nothing a line of text can say: the note's metadata, a
                // picture, a diagram's source, a query nobody reads as prose.
                is MdBlock.FrontMatter,
                is MdBlock.Mermaid,
                is MdBlock.MathBlock,
                is MdBlock.Image,
                is MdBlock.Attachment,
                is MdBlock.NoteEmbed,
                is MdBlock.ThematicBreak,
                is MdBlock.Unsupported,
                is MdBlock.Footnotes,
                -> Unit
            }
        }

        private fun list(
            list: MdBlock.ListBlock,
            indent: String,
        ) {
            list.items.forEachIndexed { index, item ->
                if (full) return
                val marker =
                    when (item.task) {
                        TaskState.CHECKED, TaskState.CANCELLED -> "☑ "
                        TaskState.UNCHECKED, TaskState.IN_PROGRESS -> "☐ "
                        TaskState.NONE -> if (list.ordered) "${list.start + index}. " else "• "
                    }
                val nested = "$indent  "
                var markerUsed = false
                item.blocks.forEach { child ->
                    when {
                        !markerUsed && child is MdBlock.Paragraph -> {
                            inlines(indent, child.inlines, prefix = marker)
                            markerUsed = true
                        }
                        !markerUsed && child is MdBlock.Heading -> {
                            add(indent, marker + plainText(child.inlines))
                            markerUsed = true
                        }
                        else -> block(child, nested)
                    }
                }
                // An item with nothing written in it is still an item.
                if (!markerUsed && item.blocks.isEmpty() && item.task != TaskState.NONE) {
                    add(indent, marker)
                }
            }
        }

        private fun clip(text: String): String =
            if (text.length <= MAX_LINE_CHARS) text else text.take(MAX_LINE_CHARS - 1).trimEnd() + "…"
    }

    /** Top-level inlines cut at every line break, soft or hard. */
    private fun splitAtBreaks(inlines: List<MdInline>): List<List<MdInline>> {
        val segments = ArrayList<List<MdInline>>()
        var current = ArrayList<MdInline>()
        inlines.forEach { inline ->
            if (inline == MdInline.SoftBreak || inline == MdInline.LineBreak) {
                segments.add(current)
                current = ArrayList()
            } else {
                current.add(inline)
            }
        }
        segments.add(current)
        return segments
    }
}
