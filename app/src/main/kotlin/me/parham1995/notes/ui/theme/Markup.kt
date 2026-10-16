package me.parham1995.notes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * naz's `@markup.*` groups, which are what it paints markdown with in the
 * editor.
 *
 * These are copied from `colors/naz.lua` rather than chosen, so a note looks
 * the same here as it does in nvim: four distinct heading colours, orange bold,
 * lime-green italics, aqua links, orange quotes. Where naz defines no group --
 * list markers, for one -- the nearest thing it does define is used, and that
 * is called out below.
 *
 * Those are dark-background colours. [DaylightMarkup] is the same roles in the
 * light scheme's accents: before it existed, a heading on paper was pale
 * yellow on off-white and could barely be read.
 */
internal open class MarkupColors(
    val Heading1: Color,
    val Heading2: Color,
    val Heading3: Color,
    val Heading4: Color,
    val Heading: Color,
    val Strong: Color,
    val Italic: Color,
    val Strike: Color,
    val Raw: Color,
    val Link: Color,
    val Math: Color,
    val Quote: Color,
    val ListMarker: Color,
) {
    fun heading(level: Int): Color =
        when (level) {
            1 -> Heading1
            2 -> Heading2
            3 -> Heading3
            4 -> Heading4
            else -> Heading
        }
}

internal object NazMarkup : MarkupColors(
    // `@markup.heading.1` .. `.4`, then the generic `@markup.heading`.
    Heading1 = Color(0xFFFFE0A0), // very_light_orange
    Heading2 = Color(0xFFFFFF88), // light_yellow
    Heading3 = Color(0xFF60D8FF), // light_blue
    Heading4 = Color(0xFFE0FFD8), // tea_green
    Heading = Color(0xFFFFE0A0), // generic, for levels 5 and 6
    // `@markup.strong`
    Strong = Color(0xFFFFB070), // lightorange
    // `@markup.italic`
    Italic = Color(0xFFB8FFD0), // lime_green
    // `@markup.strike`
    Strike = Color(0xFFF5F5F0), // white
    // `@markup.raw` -- inline code and fences
    Raw = Color(0xFFFFD850), // vivid_orange
    // `@markup.link`, `@markup.link.label`, `@markup.link.url`
    Link = Color(0xFF80E5FF), // aqua
    // `@markup.math`
    Math = Color(0xFFF5F5F0), // white
    // `@markup.quote`
    Quote = Color(0xFFFFB040), // orange
    // naz has no `@markup.list`, so markers borrow `Directory` -- the same
    // orange it uses for anything structural.
    ListMarker = Color(0xFFFFB040),
)

/** naz's markdown roles on paper, in the light scheme's darkened accents. */
internal object DaylightMarkup : MarkupColors(
    Heading1 = Daylight.Orange,
    Heading2 = Daylight.Amber,
    Heading3 = Daylight.Blue,
    Heading4 = Daylight.Green,
    Heading = Daylight.Orange,
    Strong = Daylight.Orange,
    Italic = Daylight.Green,
    Strike = Daylight.Ink,
    Raw = Daylight.Amber,
    Link = Daylight.Teal,
    Math = Daylight.Ink,
    Quote = Daylight.Orange,
    ListMarker = Daylight.Orange,
)

/** The markup colours that belong on this scheme's background. */
internal val ColorScheme.markup: MarkupColors
    get() = if (background.luminance() > HALF) DaylightMarkup else NazMarkup

private const val HALF = 0.5f

/** The markup colours of the theme being drawn. */
internal val Markup: MarkupColors
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.markup
