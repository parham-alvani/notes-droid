package me.parham1995.notes.markdown

/**
 * A note as it is printed: the lines of it a page shows, each with what kind
 * of line it is, so a PDF can set headings larger and code in a fixed width.
 *
 * For sending a note to someone who does not have Obsidian -- an address, a
 * list, a plan. Markup is dropped the way a reader would drop it; what cannot
 * be printed as text (pictures, diagrams, embeds, the front matter) is left
 * out rather than shown as syntax.
 */
object PrintedNote {
    enum class Kind { HEADING, BODY, QUOTE, CODE }

    data class Line(
        val text: String,
        val kind: Kind,
        /** Heading level, 1 to 6; zero for anything else. */
        val level: Int = 0,
        /** How many steps in -- a nested list item, a quote. */
        val indent: Int = 0,
    )

    fun of(blocks: List<MdBlock>): List<Line> = buildList { blocks.forEach { add(it, indent = 0, quoted = false) } }

    /** [quoted]: inside a quote or a callout, where prose prints as quoted prose. */
    private fun MutableList<Line>.add(
        block: MdBlock,
        indent: Int,
        quoted: Boolean,
    ) {
        val prose = if (quoted) Kind.QUOTE else Kind.BODY
        when (block) {
            is MdBlock.Heading -> text(plainText(block.inlines), Kind.HEADING, block.level, indent)
            is MdBlock.Paragraph -> text(plainText(block.inlines), prose, 0, indent)
            is MdBlock.Quote -> block.children.forEach { child -> add(child, indent + 1, quoted = true) }
            is MdBlock.Callout -> {
                val title = plainText(block.title).trim().ifEmpty { block.type.replaceFirstChar { it.uppercaseChar() } }
                text(title, Kind.QUOTE, 0, indent + 1)
                // Folded shut in the note, so shut on paper as well.
                if (!block.collapsed) block.children.forEach { child -> add(child, indent + 1, quoted = true) }
            }
            is MdBlock.ListBlock -> list(block, indent, prose, quoted)
            is MdBlock.CodeBlock ->
                block.code
                    .trimEnd()
                    .lines()
                    .forEach { add(Line(it, Kind.CODE, 0, indent)) }
            is MdBlock.Table ->
                (listOf(block.header) + block.rows).forEach { row ->
                    text(row.joinToString("  ·  ") { plainText(it).trim() }, prose, 0, indent)
                }
            else -> Unit
        }
    }

    private fun MutableList<Line>.list(
        list: MdBlock.ListBlock,
        indent: Int,
        prose: Kind,
        quoted: Boolean,
    ) {
        list.items.forEachIndexed { index, item ->
            val marker =
                when (item.task) {
                    TaskState.CHECKED, TaskState.CANCELLED -> "☑ "
                    TaskState.UNCHECKED, TaskState.IN_PROGRESS -> "☐ "
                    TaskState.NONE -> if (list.ordered) "${list.start + index}. " else "• "
                }
            var marked = false
            item.blocks.forEach { child ->
                if (!marked && child is MdBlock.Paragraph) {
                    text(marker + plainText(child.inlines), prose, 0, indent)
                    marked = true
                } else {
                    add(child, indent + 1, quoted)
                }
            }
            if (!marked) text(marker, prose, 0, indent)
        }
    }

    private fun MutableList<Line>.text(
        text: String,
        kind: Kind,
        level: Int,
        indent: Int,
    ) {
        val clean = text.trim()
        if (clean.isNotEmpty()) add(Line(clean, kind, level, indent))
    }
}
