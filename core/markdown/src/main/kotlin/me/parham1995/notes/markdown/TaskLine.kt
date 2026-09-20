package me.parham1995.notes.markdown

/**
 * One task as it is actually written in the file.
 *
 * Everything else in the app works with a task as the index stored it -- text
 * with the markup and the trailing emoji taken off -- and none of that can be
 * written back. Ticking a task means editing the line that produced it, so this
 * is the piece that goes the other way: from a raw line to what the index would
 * have made of it, and from a raw line to the same line completed.
 */
object TaskLine {
    /** `- [ ] `, `* [x] `, `1. [/] ` -- marker, state, and the rest. */
    private val LINE = Regex("""^(\s*(?:[-*+]|\d+[.)])\s+)\[([ xX/\-])]\s*(.*)$""")

    fun isTask(raw: String): Boolean = LINE.matches(raw)

    fun isOpen(raw: String): Boolean =
        LINE
            .find(raw)
            ?.groupValues
            ?.get(2)
            ?.let { it == " " || it == "/" } ?: false

    /** Whether completing it would drop a repeat rule the app cannot re-create. */
    fun isRecurring(raw: String): Boolean =
        LINE
            .find(raw)
            ?.groupValues
            ?.get(3)
            ?.contains(RECUR) ?: false

    /**
     * The same line, ticked and dated, or null when it is not an open task or
     * is already done.
     *
     * The date goes on the end because that is where the Tasks plugin's
     * metadata lives and where every other completed line in this vault carries
     * it.
     */
    fun complete(
        raw: String,
        today: String,
    ): String? {
        val match = LINE.find(raw) ?: return null
        val (prefix, state, body) = match.destructured
        if (state != " " && state != "/") return null
        if (body.contains(DONE)) return null
        return "$prefix[x] ${body.trimEnd()} $DONE $today"
    }

    /**
     * What the index would call this line, so a stored task can be matched back
     * to the line it came from.
     *
     * Parsed rather than pattern-matched: the stored text has had wikilinks
     * reduced to their alias, emphasis unwrapped and the emoji metadata
     * stripped, and re-deriving that with a regular expression would be the
     * renderer written a second time and wrong in a different way.
     */
    fun indexedText(raw: String): String? {
        if (!isTask(raw)) return null
        val note = MarkdownParser.parseNote(raw.trimStart())
        return TaskExtractor.extract(note).firstOrNull()?.text
    }

    private const val DONE = "✅"
    private const val RECUR = "🔁"
}
