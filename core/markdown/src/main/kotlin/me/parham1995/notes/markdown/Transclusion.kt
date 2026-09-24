package me.parham1995.notes.markdown

/** What of another note an `![[Note#Heading]]` embed shows. */
object Transclusion {
    /**
     * The blocks [heading] names in [blocks]: the heading itself and everything
     * under it, up to the next heading of the same level or higher.
     *
     * No heading means the whole note. `^id` means the one block that id
     * names, looked up in [targets]. A heading that is not there -- renamed
     * since the link was written -- is null, so the caller can fall back to a
     * link rather than showing the wrong part of the note as though it were
     * the right one.
     *
     * Matched on the heading's text, trimmed and ignoring case, which is how a
     * `[[Note#Heading]]` link finds its heading too. `Parent#Child` finds the
     * child under that parent -- see [HeadingPath].
     */
    fun section(
        blocks: List<MdBlock>,
        heading: String?,
        targets: Map<String, MdBlock> = emptyMap(),
    ): List<MdBlock>? {
        val wanted = heading?.trim().orEmpty()
        if (wanted.isEmpty()) return blocks
        if (wanted.startsWith('^')) return targets[wanted.substring(1)]?.let { listOf(it) }

        val positions = blocks.indices.filter { blocks[it] is MdBlock.Heading }
        val found =
            HeadingPath.find(positions.map { (blocks[it] as MdBlock.Heading).let { h -> h.level to h.text } }, wanted)
                ?: return null
        val start = positions[found]
        val level = (blocks[start] as MdBlock.Heading).level
        // The footnotes gathered at the end of the note belong to the whole
        // note, not to its last section.
        val end =
            (start + 1 until blocks.size)
                .firstOrNull { index ->
                    val block = blocks[index]
                    block is MdBlock.Footnotes || (block as? MdBlock.Heading)?.let { it.level <= level } == true
                } ?: blocks.size
        return blocks.subList(start, end)
    }
}

/**
 * Finds a heading by the path a link gives it.
 *
 * `[[Note#Heading]]` names one heading; `[[Note#Chapter#Section]]` names the
 * Section under Chapter, which is how Obsidian tells apart two headings of
 * the same name -- every "Notes" under every chapter of a long document.
 * Each part is looked for inside the section the part before it found.
 */
object HeadingPath {
    /**
     * The index into [headings] -- each a level and its text, in document
     * order -- of the heading [path] names, or null when there is none.
     *
     * The path is tried whole first: a heading may have a `#` in it, `C#` for
     * one, and splitting that would look for a heading called `C`.
     */
    fun find(
        headings: List<Pair<Int, String>>,
        path: String,
    ): Int? {
        val whole = path.trim()
        if (whole.isEmpty()) return null
        headings.indexOfFirst { matches(it.second, whole) }.takeIf { it >= 0 }?.let { return it }

        val parts = whole.split('#').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        var from = 0
        var until = headings.size
        var found: Int? = null
        for (part in parts) {
            val at = (from until until).firstOrNull { matches(headings[it].second, part) } ?: return null
            found = at
            val level = headings[at].first
            from = at + 1
            until = (at + 1 until until).firstOrNull { headings[it].first <= level } ?: until
        }
        return found
    }

    private fun matches(
        text: String,
        wanted: String,
    ): Boolean = text.trim().equals(wanted, ignoreCase = true)
}
