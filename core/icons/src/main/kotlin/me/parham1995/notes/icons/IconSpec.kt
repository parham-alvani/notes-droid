package me.parham1995.notes.icons

/**
 * A colour exactly as Iconic records it, with no interpretation.
 *
 * Turning a name into an actual colour is a theming decision, so it belongs in
 * the app rather than here -- this module has no opinion about what "cyan"
 * looks like against a particular background.
 */
sealed interface IconColor {
    /** One of Iconic's named colours. This vault uses cyan, green, orange and purple. */
    data class Named(
        val name: String,
    ) : IconColor

    /** A colour taken from the RGB picker, as `0xRRGGBB`. */
    data class Rgb(
        val value: Int,
    ) : IconColor
}

/** What to draw beside an item. */
sealed interface IconSpec {
    val color: IconColor?

    /**
     * A Lucide glyph, named the way Lucide names it -- `piggy-bank`, with
     * Iconic's `lucide-` prefix already stripped.
     */
    data class Glyph(
        val name: String,
        override val color: IconColor? = null,
    ) : IconSpec

    /** A literal emoji. Iconic stores these as the characters themselves. */
    data class Emoji(
        val text: String,
        override val color: IconColor? = null,
    ) : IconSpec
}
