package me.parham1995.notes.markdown

/**
 * The three edits the app is allowed to make to a note, as pure functions of
 * the file's current text.
 *
 * Writing from a phone is safe here only because none of these is a patch. Each
 * one is re-run against whatever the file says at the moment of the write, so a
 * change that was queued offline on Monday and pushed on Wednesday lands in
 * Wednesday's file rather than overwriting two days of work with Monday's copy.
 *
 * Every one of them returns null when the file already says what it wanted it
 * to say. That is a quiet success -- a task ticked at the desk before the phone
 * caught up is exactly the case, and it must not read as a failure.
 */
object VaultEdits {
    /**
     * Replaces the line reading [anchor] with [replacement], keeping whatever
     * indentation the line already had.
     *
     * Matched on the line's own text rather than on a line number, because
     * between reading a task and writing it back the note may well have grown a
     * paragraph above it. Trimmed on both sides so a sub-task indented two
     * levels still matches the line that was read.
     */
    fun replaceLine(
        anchor: String,
        replacement: String,
    ): (String?) -> String? = replaceLineWith(anchor, listOf(replacement))

    /**
     * The same, but the line becomes several.
     *
     * Completing a repeating task is the reason: one line goes in, the next
     * occurrence and the finished one come out. Each keeps the indentation the
     * original had, so a nested sub-task stays nested.
     */
    fun replaceLineWith(
        anchor: String,
        replacement: List<String>,
    ): (String?) -> String? {
        val wanted = anchor.trim()
        val becomes = replacement.map { it.trim() }.filter { it.isNotEmpty() }
        if (becomes.isEmpty()) return { null }
        return { current ->
            when {
                current == null -> null
                // Already said: the last line is the one a second run would
                // produce, so finding it means the edit has happened.
                current.lines().any { it.trim() == becomes.last() } -> null
                else -> {
                    val lines = current.lines()
                    val at = lines.indexOfFirst { it.trim() == wanted }
                    if (at < 0) {
                        null
                    } else {
                        val indent = lines[at].takeWhile { it == ' ' || it == '\t' }
                        lines
                            .toMutableList()
                            .apply {
                                removeAt(at)
                                addAll(at, becomes.map { indent + it })
                            }.joinToString("\n")
                    }
                }
            }
        }
    }

    /**
     * Adds [line] at the end of the `## [section]` section, or at the end of
     * the file when there is no such heading.
     *
     * The end of the section rather than the top of it: this vault's task files
     * are read top to bottom as a history of a project, and an insert at the
     * front puts the newest work where the oldest belongs.
     */
    fun addUnder(
        section: String,
        line: String,
    ): (String?) -> String? {
        val wanted = section.trim()
        val entry = line.trim()
        return { current ->
            val text = current.orEmpty()
            if (text.lines().any { it.trim() == entry }) {
                null
            } else {
                val lines = text.lines().toMutableList()
                val headings = headingLines(lines)
                val start = headings.firstOrNull { lines[it].headingText() == wanted } ?: -1
                if (wanted.isEmpty() || start < 0) {
                    // Blank line after the new heading: markdownlint requires
                    // one, and this vault runs it over every commit.
                    val opening = if (wanted.isEmpty()) emptyList() else listOf("## $wanted", "")
                    appendLines(lines, opening + entry)
                } else {
                    val end = sectionEnd(lines, start, headings)
                    lines.add(end, entry)
                    lines.joinToString("\n")
                }
            }
        }
    }

    /**
     * Appends [block] to the end of the file, creating it with [heading] when
     * it is not there at all.
     *
     * This is the scratchpad's whole write path: a capture that has to work
     * before the note exists, because the first thing anyone does with a
     * scratchpad is write in it, not create it.
     */
    fun append(
        block: String,
        heading: String? = null,
    ): (String?) -> String? {
        val body = block.trim()
        return { current ->
            if (body.isEmpty()) {
                null
            } else if (current.isNullOrBlank()) {
                listOfNotNull(heading?.takeIf { it.isNotBlank() }?.let { "# $it" }, "", body, "")
                    .joinToString("\n")
                    .trimStart('\n')
            } else {
                appendLines(current.lines().toMutableList(), listOf(body))
            }
        }
    }

    /**
     * Appends [additions] after the file's last non-blank line, separated by
     * exactly one blank line and ending in exactly one newline.
     *
     * Prettier runs over this vault on every commit and will rewrite anything
     * else, which turns a one-line capture into a diff touching the end of the
     * file twice.
     */
    private fun appendLines(
        lines: MutableList<String>,
        additions: List<String>,
    ): String {
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        // No blank between two list items: it would split one list into two and
        // make it loose, which changes how the whole list renders. A task added
        // to the end of a list of tasks belongs in that list.
        val joins = lines.lastOrNull()?.isListItem() == true && additions.first().isListItem()
        if (lines.isNotEmpty() && !joins) lines.add("")
        lines.addAll(additions)
        lines.add("")
        return lines.joinToString("\n")
    }

    private fun String.isListItem(): Boolean {
        val body = trimStart()
        return body.startsWith("- ") || body.startsWith("* ") || body.startsWith("+ ")
    }

    /**
     * Where the section starting at [start] ends: the line after its last
     * non-blank line, so an insert lands under the content rather than after
     * the blank line that separates it from the next heading.
     */
    private fun sectionEnd(
        lines: List<String>,
        start: Int,
        headings: List<Int>,
    ): Int {
        var end = headings.firstOrNull { it > start } ?: lines.size
        while (end > start + 1 && lines[end - 1].isBlank()) end--
        return end
    }

    /**
     * Which lines are ATX headings, skipping fenced code.
     *
     * Fence-aware because a third of this vault's lines sit inside a fence and
     * a great many of them are shell comments. Reading `# install the agent`
     * as a heading would put a new task in the middle of a bash block.
     */
    private fun headingLines(lines: List<String>): List<Int> {
        val out = mutableListOf<Int>()
        var fence: String? = null
        lines.forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            val ticks = trimmed.takeWhile { it == '`' || it == '~' }
            if (ticks.length >= FENCE_MIN) {
                val open = fence
                when {
                    open == null -> fence = ticks
                    // A closing fence must be at least as long as the one that
                    // opened the block, which is what lets a ````markdown
                    // example contain ``` of its own.
                    ticks.length >= open.length && ticks.first() == open.first() -> fence = null
                }
                return@forEachIndexed
            }
            if (fence == null && trimmed.startsWith("#") && trimmed.headingText().isNotEmpty()) {
                val hashes = trimmed.takeWhile { it == '#' }
                if (hashes.length <= MAX_HEADING_LEVEL && trimmed.getOrNull(hashes.length) == ' ') out += index
            }
        }
        return out
    }

    private fun String.headingText(): String = trimStart().trimStart('#').trim()

    private const val FENCE_MIN = 3
    private const val MAX_HEADING_LEVEL = 6
}
