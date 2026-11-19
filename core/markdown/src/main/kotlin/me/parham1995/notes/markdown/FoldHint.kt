package me.parham1995.notes.markdown

/**
 * What a folded heading says about the section under it, after its own text:
 *
 * ```text
 * How the text begins… · 240 words · 3 sections
 * ```
 *
 * A note that opens with every section shut is its own table of contents, and
 * a heading alone does not say whether a line or a chapter is under it. The
 * rules are the ones Daftar for Linux and the Mac follow (`foldHint` in their
 * shared page), written down with the cases every app must pass in
 * `folded-sections.md` in notes-linux's `docs/specs/`; change one here and
 * theirs have to follow.
 *
 * The hint is never part of the heading: [MdBlock.Heading.text], which links
 * and the outline go by, is not touched.
 */
object FoldHint {
    /** The longest the beginning of the text may be before it is cut. */
    const val LIMIT = 64

    private const val DOTS = "…"
    private const val JOIN = " · "

    /** What is dropped at the cut, so no mark is left hanging before the dots. */
    private const val LOOSE = ".,;:!?،؛"

    private val SPACE = Regex("""\s+""")

    /**
     * What a hint is made of, before it is put into words: the beginning of
     * the text as it is shown, and the two counts, each null when the hint
     * leaves it out. Apart from [say] so the screen can count in the phone's
     * language and join the parts another way for a screen reader.
     */
    data class Parts(
        val start: String,
        val words: Int?,
        val sections: Int?,
    ) {
        /** Nothing to say: a section with no text and no headings, which shows the dots alone. */
        val isEmpty: Boolean get() = start.isEmpty() && words == null && sections == null

        /** The hint as the spec writes it: `How the text begins… · 240 words · 3 sections`. */
        fun say(): String =
            join(
                words = words?.let { if (it == 1) "1 word" else "$it words" },
                sections = sections?.let { if (it == 1) "1 section" else "$it sections" },
            )

        /** The parts that are there, in order, with the counts in the words given for them. */
        fun join(
            words: String?,
            sections: String?,
            separator: String = JOIN,
        ): String = listOfNotNull(start.ifEmpty { null }, words, sections).joinToString(separator).ifEmpty { DOTS }
    }

    /**
     * The parts of the hint: how the text begins ([first]), then how many
     * [words] and [sections] are under the heading.
     *
     * The beginning is cut at the last space before [LIMIT] characters, or at
     * [LIMIT] when it is one long word. The words are left out when the
     * beginning already shows all of them, and the sections when there are
     * none.
     */
    fun parts(
        first: String,
        words: Int,
        sections: Int,
    ): Parts {
        var start = first.trim()
        var cut = false
        if (start.length > LIMIT) {
            val head = start.take(LIMIT)
            val atWord = if (' ' in head) head.substringBeforeLast(' ') else head
            start = atWord.trimEnd { it.isWhitespace() || it in LOOSE } + DOTS
            cut = true
        }
        val shown = if (start.isEmpty()) 0 else start.split(SPACE).size
        return Parts(
            start = start,
            words = words.takeIf { it > shown || cut },
            sections = sections.takeIf { it > 0 },
        )
    }

    /** The words of the hint, as every Daftar app says them. A section holding nothing says the dots alone. */
    fun text(
        first: String,
        words: Int,
        sections: Int,
    ): String = parts(first, words, sections).say()

    /**
     * The hint of the heading at [heading] in [blocks]: the first block under
     * it that has any text, the words of every block down to the next heading
     * as big or bigger, and the headings on the way, at any depth. The
     * headings' own text is not counted. Null when the block is no heading.
     */
    fun of(
        blocks: List<MdBlock>,
        heading: Int,
    ): Parts? {
        if (blocks.getOrNull(heading) !is MdBlock.Heading) return null
        var first = ""
        var words = 0
        var sections = 0
        for (i in heading + 1 until HeadingFolds.sectionEnd(blocks, heading)) {
            val block = blocks[i]
            if (block is MdBlock.Heading) {
                sections += 1
                continue
            }
            val text = words(block)
            if (text.isEmpty()) continue
            words += text.size
            if (first.isEmpty()) first = text.joinToString(" ")
        }
        return parts(first, words, sections)
    }

    /** The words a reader sees in [block]. Front matter, pictures and diagrams have none. */
    private fun words(block: MdBlock): List<String> = said(block).split(SPACE).filter { it.isNotEmpty() }

    private fun said(block: MdBlock): String =
        when (block) {
            is MdBlock.Paragraph -> plainText(block.inlines)
            is MdBlock.Heading -> plainText(block.inlines)
            is MdBlock.CodeBlock -> block.code
            is MdBlock.MathBlock -> block.latex
            is MdBlock.Quote -> block.children.joinToString(" ") { said(it) }
            is MdBlock.Callout -> plainText(block.title) + " " + block.children.joinToString(" ") { said(it) }
            is MdBlock.ListBlock ->
                block.items.joinToString(" ") { item ->
                    (item.blocks.map { said(it) } + listOfNotNull(item.reason)).joinToString(" ")
                }
            is MdBlock.Table -> (block.header + block.rows.flatten()).joinToString(" ") { plainText(it) }
            is MdBlock.Footnotes ->
                block.entries.joinToString(" ") { entry -> entry.blocks.joinToString(" ") { said(it) } }
            is MdBlock.Attachment -> block.label
            is MdBlock.NoteEmbed -> block.label
            is MdBlock.FrontMatter,
            is MdBlock.Mermaid,
            is MdBlock.Image,
            is MdBlock.ThematicBreak,
            is MdBlock.TasksQuery,
            is MdBlock.BaseView,
            is MdBlock.Unsupported,
            -> ""
        }
}
