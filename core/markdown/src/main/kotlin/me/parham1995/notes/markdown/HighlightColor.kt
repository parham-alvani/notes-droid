package me.parham1995.notes.markdown

/**
 * Obsidian's highlight colours: `==🟡text==` is a yellow highlight.
 *
 * The colour is a circle emoji at the very start of the highlight, one of six.
 * It names the colour and is not part of the text, so it is taken off -- along
 * with the space after it, written or not -- and never reaches the renderer,
 * the search text or a task's stored text.
 */
enum class HighlightColor(
    val emoji: String,
) {
    RED("🔴"),
    ORANGE("🟠"),
    YELLOW("🟡"),
    GREEN("🟢"),
    BLUE("🔵"),
    PURPLE("🟣"),
    ;

    companion object {
        private const val VARIATION_SELECTOR = '️'

        /**
         * The colour a highlight's content names, and the content without it.
         * A highlight that starts with anything else is the plain one, as is a
         * highlight that holds nothing but the emoji.
         */
        fun of(children: List<MdInline>): Pair<HighlightColor?, List<MdInline>> {
            val first = children.firstOrNull() as? MdInline.Text ?: return null to children
            val color = entries.firstOrNull { first.text.startsWith(it.emoji) } ?: return null to children
            val rest =
                first.text
                    .removePrefix(color.emoji)
                    .removePrefix(VARIATION_SELECTOR.toString())
                    .trimStart(' ')
            val content = if (rest.isEmpty()) children.drop(1) else listOf(MdInline.Text(rest)) + children.drop(1)
            if (content.isEmpty()) return null to children
            return color to content
        }
    }
}
