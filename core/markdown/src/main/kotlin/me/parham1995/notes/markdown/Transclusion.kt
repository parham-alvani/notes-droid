package me.parham1995.notes.markdown

/** What of another note an `![[Note#Heading]]` embed shows. */
object Transclusion {
    /**
     * The blocks [heading] names in [blocks]: the heading itself and everything
     * under it, up to the next heading of the same level or higher.
     *
     * No heading means the whole note. A heading that is not there -- renamed
     * since the link was written, or a `^block` reference, which the index does
     * not track -- is null, so the caller can fall back to a link rather than
     * showing the wrong part of the note as though it were the right one.
     *
     * Matched on the heading's text, trimmed and ignoring case, which is how a
     * `[[Note#Heading]]` link finds its heading too.
     */
    fun section(
        blocks: List<MdBlock>,
        heading: String?,
    ): List<MdBlock>? {
        val wanted = heading?.trim().orEmpty()
        if (wanted.isEmpty()) return blocks
        val start =
            blocks.indexOfFirst { it is MdBlock.Heading && it.text.trim().equals(wanted, ignoreCase = true) }
        if (start < 0) return null
        val level = (blocks[start] as MdBlock.Heading).level
        val end =
            (start + 1 until blocks.size)
                .firstOrNull { index -> (blocks[index] as? MdBlock.Heading)?.let { it.level <= level } == true }
                ?: blocks.size
        return blocks.subList(start, end)
    }
}
