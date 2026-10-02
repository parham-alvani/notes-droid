package me.parham1995.notes.markdown

/**
 * Folding a note at its headings, the way Obsidian's reading view does.
 *
 * A heading owns everything after it up to the next heading of its own rank or
 * higher, and folding it hides that stretch. The heading itself stays, so there
 * is something to unfold from. Everything here is a function of the block list
 * and a set of folded positions, and nothing is stored on the blocks: the same
 * note is drawn in an embed and a widget, and neither of those folds.
 *
 * Positions, not ids, throughout -- the reader scrolls by position, and a
 * block's id is handed out to nested blocks too.
 */
object HeadingFolds {
    /**
     * Where the section headed by [blocks]`[heading]` ends, exclusive: the next
     * heading of the same or a higher level, or the end of the note. A block
     * that is not a heading has no section, so its end is the position after it.
     */
    fun sectionEnd(
        blocks: List<MdBlock>,
        heading: Int,
    ): Int {
        val level = (blocks.getOrNull(heading) as? MdBlock.Heading)?.level ?: return heading + 1
        for (i in heading + 1 until blocks.size) {
            val next = blocks[i] as? MdBlock.Heading ?: continue
            if (next.level <= level) return i
        }
        return blocks.size
    }

    /** Whether there is anything under the heading at [heading] to fold away. */
    fun hasSection(
        blocks: List<MdBlock>,
        heading: Int,
    ): Boolean = blocks.getOrNull(heading) is MdBlock.Heading && sectionEnd(blocks, heading) > heading + 1

    /**
     * The positions still shown when the headings in [folded] are folded.
     *
     * A folded heading is kept; what it owns is dropped. A heading inside a
     * folded section is gone with the section, folded or not, so unfolding the
     * outer one brings it back in whatever state it was left.
     */
    fun visible(
        blocks: List<MdBlock>,
        folded: Set<Int>,
    ): List<Int> {
        if (folded.isEmpty()) return blocks.indices.toList()
        val shown = ArrayList<Int>(blocks.size)
        var i = 0
        while (i < blocks.size) {
            shown += i
            i = if (i in folded) sectionEnd(blocks, i) else i + 1
        }
        return shown
    }

    /**
     * [folded] with every fold that hides the block at [target] undone, so the
     * block can be scrolled to. A heading that is itself folded is still on
     * screen, so asking for it opens nothing.
     */
    fun revealing(
        blocks: List<MdBlock>,
        folded: Set<Int>,
        target: Int,
    ): Set<Int> = folded.filterNot { it < target && target < sectionEnd(blocks, it) }.toSet()
}
