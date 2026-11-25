package me.parham1995.notes.markdown

/**
 * A table, a code block or a picture wider than the prose may grow past the
 * measure into the room beside it: `wide-blocks.md` in notes-linux's
 * `docs/specs/`, which Daftar for Linux and the Mac follow too.
 *
 * Where such a block stands: its [width] and its [offset] from the pane's
 * left edge, in dp.
 */
data class WideBlock(
    val width: Float,
    val offset: Float,
) {
    companion object {
        /**
         * The place of a block [natural] wide, with the measure [measure], in
         * a pane [pane] wide that keeps [side] at each side, and blocks
         * allowed [limit] (`appearance.block_width` em at the text size; 0 is
         * off). Null when the block keeps the measure, as before: it fits, or
         * there is no measure, or no room past it.
         */
        fun place(
            natural: Float,
            measure: Float,
            pane: Float,
            side: Float,
            limit: Float,
        ): WideBlock? {
            if (limit <= 0f || measure <= 0f || natural <= measure) return null
            val most = minOf(limit, pane - 2 * side)
            if (most <= measure) return null
            val width = minOf(natural, most)
            return WideBlock(width, (pane - width) / 2)
        }

        /**
         * Whether [block], standing in the note itself, may grow: a table, a
         * fenced code block, a diagram, display math, a picture alone on its
         * line and a `[!figure]` callout. A block inside a list, a quote or
         * another callout is never asked, so it keeps the measure.
         */
        fun isWide(block: MdBlock): Boolean =
            when (block) {
                is MdBlock.Table, is MdBlock.CodeBlock, is MdBlock.Mermaid, is MdBlock.MathBlock -> true
                is MdBlock.Image -> !block.inSentence
                is MdBlock.Callout -> block.kind == CalloutKind.FIGURE
                else -> false
            }

        /**
         * An SVG's own width, in CSS pixels: the root's `width` when it is a
         * number (with `px` or none), else the width of its `viewBox`. Null
         * when it says neither, or only a percentage.
         */
        fun svgWidth(svg: String): Float? {
            val root = SVG_ROOT.find(svg)?.value ?: return null
            attribute(root, "width")
                ?.removeSuffix("px")
                ?.toFloatOrNull()
                ?.takeIf { it > 0f }
                ?.let { return it }
            return attribute(root, "viewBox")
                ?.trim()
                ?.split(SEPARATORS)
                ?.getOrNull(2)
                ?.toFloatOrNull()
                ?.takeIf { it > 0f }
        }

        private fun attribute(
            tag: String,
            name: String,
        ): String? =
            Regex("""\s$name\s*=\s*(["'])(.*?)\1""")
                .find(tag)
                ?.groupValues
                ?.get(2)
                ?.trim()

        private val SVG_ROOT = Regex("""<svg\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val SEPARATORS = Regex("""[\s,]+""")
    }
}
