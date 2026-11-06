package me.parham1995.notes.markdown

/**
 * A note as it is printed: the lines of it a page shows, each with what kind
 * of line it is, so a PDF can set headings larger and code in a fixed width.
 *
 * For sending a note to someone who does not have Obsidian -- an address, a
 * list, a plan. Markup is dropped the way a reader would drop it; what cannot
 * be printed as text (diagrams, embeds of notes, the front matter) is left
 * out rather than shown as syntax. A picture from the vault is printed; one
 * from the web is not asked for. The page follows `export.md` in
 * notes-linux's `docs/specs/` as far as Android's own PDF writer can.
 */
object PrintedNote {
    enum class Kind { HEADING, BODY, QUOTE, CODE, IMAGE }

    data class Line(
        /** The words; for a picture, its alt text. */
        val text: String,
        val kind: Kind,
        /** Heading level, 1 to 6; zero for anything else. */
        val level: Int = 0,
        /** How many steps in -- a nested list item, a quote. */
        val indent: Int = 0,
        /** A task that is done or cancelled: printed greyed. */
        val faded: Boolean = false,
        /** The kind of callout the line is in, for the colour of its bar; null outside one. */
        val callout: CalloutKind? = null,
        /** A picture's path in the vault, for [Kind.IMAGE]. */
        val image: String? = null,
        /** The width a picture asked for (`![[x.png|300]]`), in points. */
        val width: Int? = null,
    )

    /**
     * [blocks] as printed lines. A first `# Heading` that says [title] again
     * is left out: the page's head has the title.
     */
    fun of(
        blocks: List<MdBlock>,
        title: String? = null,
    ): List<Line> {
        val first = blocks.firstOrNull { it !is MdBlock.FrontMatter }
        val repeated =
            first is MdBlock.Heading &&
                first.level == 1 &&
                title != null &&
                plainText(first.inlines).trim().equals(title.trim(), ignoreCase = true)
        return buildList {
            blocks.forEach { block ->
                if (!(repeated && block === first)) add(block, indent = 0, quoted = false, callout = null)
            }
        }
    }

    /**
     * A task's line with the Tasks plugin's signs as words -- `📅 2026-11-02`
     * is `due 2026-11-02` -- as the Linux export prints them.
     */
    fun signsAsWords(text: String): String =
        TASK_SIGNS
            .fold(text) { line, (sign, word) -> line.replace(Regex(Regex.escape(sign) + "️?"), word) }
            .replace(SPACES, " ")
            .trim()

    /** [quoted]: inside a quote or a callout, where prose prints as quoted prose. */
    private fun MutableList<Line>.add(
        block: MdBlock,
        indent: Int,
        quoted: Boolean,
        callout: CalloutKind?,
    ) {
        val prose = if (quoted) Kind.QUOTE else Kind.BODY
        when (block) {
            is MdBlock.Heading -> text(plainText(block.inlines), Kind.HEADING, block.level, indent)
            is MdBlock.Paragraph -> text(plainText(block.inlines), prose, 0, indent, callout = callout)
            is MdBlock.Quote -> block.children.forEach { child -> add(child, indent + 1, quoted = true, callout) }
            is MdBlock.Callout -> {
                val title = plainText(block.title).trim().ifEmpty { block.type.replaceFirstChar { it.uppercaseChar() } }
                text(title, Kind.QUOTE, 0, indent + 1, callout = block.kind)
                // Folded shut in the note, so shut on paper as well.
                if (!block.collapsed) {
                    block.children.forEach { child ->
                        add(child, indent + 1, quoted = true, block.kind)
                    }
                }
            }
            is MdBlock.ListBlock -> list(block, indent, prose, quoted, callout)
            is MdBlock.CodeBlock ->
                block.code
                    .trimEnd()
                    .lines()
                    .forEach { add(Line(it, Kind.CODE, 0, indent, callout = callout)) }
            is MdBlock.Table ->
                (listOf(block.header) + block.rows).forEach { row ->
                    text(row.joinToString("  ·  ") { plainText(it).trim() }, prose, 0, indent, callout = callout)
                }
            // A picture of the vault; one from the web, or written into the
            // note, is not fetched for paper.
            is MdBlock.Image ->
                if (!RemoteImages.isRemote(block.path) && !block.path.startsWith("data:")) {
                    add(
                        Line(
                            block.alt.orEmpty(),
                            Kind.IMAGE,
                            0,
                            indent,
                            callout = callout,
                            image = block.path,
                            width = block.width,
                        ),
                    )
                }
            else -> Unit
        }
    }

    private fun MutableList<Line>.list(
        list: MdBlock.ListBlock,
        indent: Int,
        prose: Kind,
        quoted: Boolean,
        callout: CalloutKind?,
    ) {
        list.items.forEachIndexed { index, item ->
            val marker =
                when (item.task) {
                    TaskState.CHECKED -> "☑ "
                    TaskState.CANCELLED -> "☒ "
                    TaskState.UNCHECKED, TaskState.IN_PROGRESS -> "☐ "
                    TaskState.NONE -> if (list.ordered) "${list.start + index}. " else "• "
                }
            val task = item.task != TaskState.NONE
            val faded = item.task == TaskState.CHECKED || item.task == TaskState.CANCELLED
            var marked = false
            item.blocks.forEach { child ->
                if (!marked && child is MdBlock.Paragraph) {
                    // A task's dates and priority are taken out of its text
                    // as it is read; on paper they are words after it.
                    val meta = item.taskMeta.joinToString(" ") { signsAsWords("${it.emoji} ${it.value}") }
                    val words =
                        if (task) {
                            listOf(
                                signsAsWords(plainText(child.inlines)),
                                meta,
                            ).filter { it.isNotBlank() }.joinToString(" ")
                        } else {
                            plainText(child.inlines)
                        }
                    text(marker + words, prose, 0, indent, faded, callout)
                    marked = true
                } else {
                    add(child, indent + 1, quoted, callout)
                }
            }
            if (!marked) text(marker, prose, 0, indent, faded, callout)
        }
    }

    private fun MutableList<Line>.text(
        text: String,
        kind: Kind,
        level: Int,
        indent: Int,
        faded: Boolean = false,
        callout: CalloutKind? = null,
    ) {
        val clean = text.trim()
        if (clean.isNotEmpty()) add(Line(clean, kind, level, indent, faded, callout))
    }

    private val SPACES = Regex(" {2,}")

    /** The Tasks plugin's signs, and the word each is printed as. */
    private val TASK_SIGNS =
        listOf(
            "📅" to "due",
            "⏳" to "scheduled",
            "🛫" to "starts",
            "➕" to "created",
            "✅" to "done",
            "❌" to "cancelled",
            "🔁" to "every",
            "🆔" to "id",
            "⛔" to "after",
            "🔺" to "highest",
            "⏫" to "high",
            "🔼" to "medium",
            "🔽" to "low",
            "⏬" to "lowest",
        )
}
