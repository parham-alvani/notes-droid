package me.parham1995.notes.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * naz's `@markup.*` groups, which are what it paints markdown with in the
 * editor.
 *
 * These are copied from `colors/naz.lua` rather than chosen, so a note looks
 * the same here as it does in nvim: four distinct heading colours, orange bold,
 * lime-green italics, aqua links, orange quotes. Where naz defines no group --
 * list markers, for one -- the nearest thing it does define is used, and that
 * is called out below.
 */
internal object Markup {
    /** `@markup.heading.1` .. `.4`, then the generic `@markup.heading`. */
    val Heading1 = Color(0xFFFFE0A0) // very_light_orange
    val Heading2 = Color(0xFFFFFF88) // light_yellow
    val Heading3 = Color(0xFF60D8FF) // light_blue
    val Heading4 = Color(0xFFE0FFD8) // tea_green
    val Heading = Color(0xFFFFE0A0) // generic, for levels 5 and 6

    /** `@markup.strong` */
    val Strong = Color(0xFFFFB070) // lightorange

    /** `@markup.italic` */
    val Italic = Color(0xFFB8FFD0) // lime_green

    /** `@markup.strike` */
    val Strike = Color(0xFFF5F5F0) // white

    /** `@markup.raw` -- inline code and fences */
    val Raw = Color(0xFFFFD850) // vivid_orange

    /** `@markup.link`, `@markup.link.label`, `@markup.link.url` */
    val Link = Color(0xFF80E5FF) // aqua

    /** `@markup.math` */
    val Math = Color(0xFFF5F5F0) // white

    /** `@markup.quote` */
    val Quote = Color(0xFFFFB040) // orange

    /**
     * naz has no `@markup.list`, so markers borrow `Directory` -- the same
     * orange it uses for anything structural.
     */
    val ListMarker = Color(0xFFFFB040)

    fun heading(level: Int): Color =
        when (level) {
            1 -> Heading1
            2 -> Heading2
            3 -> Heading3
            4 -> Heading4
            else -> Heading
        }
}
