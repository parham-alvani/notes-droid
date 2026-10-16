package me.parham1995.notes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * naz's accents by name, in the shade that belongs on the background being
 * drawn on.
 *
 * Callout kinds, task states, date chips, the task buckets and the graph all
 * name a colour -- red for overdue, green for done -- and they used to name
 * [Naz]'s directly. Those were picked to glow on `#323232` and on paper half of
 * them could not be read: a done date was spring green on white. Asking for
 * the accent here instead gets naz's own on the dark scheme and a darkened one
 * on the light.
 */
internal class AccentColors(
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val vividYellow: Color,
    val springGreen: Color,
    val chartreuse: Color,
    val limeGreen: Color,
    val blue: Color,
    val aqua: Color,
    val purple: Color,
    val pink: Color,
    val grey: Color,
    val warmGrey: Color,
)

internal val NazAccents =
    AccentColors(
        red = Naz.Red,
        orange = Naz.Orange,
        yellow = Naz.Yellow,
        vividYellow = Naz.VividYellow,
        springGreen = Naz.SpringGreen,
        chartreuse = Naz.Chartreuse,
        limeGreen = Naz.LimeGreen,
        blue = Naz.Blue,
        aqua = Naz.Aqua,
        purple = Naz.Purple,
        pink = Naz.Pink,
        grey = Naz.Grey,
        warmGrey = Naz.WarmGrey,
    )

/** The same names on paper, from [Daylight]'s darkened accents. */
internal val DaylightAccents =
    AccentColors(
        red = Daylight.Red,
        orange = Daylight.Orange,
        yellow = Daylight.Amber,
        vividYellow = Daylight.Amber,
        springGreen = Daylight.Green,
        chartreuse = Daylight.Olive,
        limeGreen = Daylight.Green,
        blue = Daylight.Blue,
        aqua = Daylight.Teal,
        purple = Daylight.Purple,
        pink = Daylight.Pink,
        grey = Daylight.FadedInk,
        warmGrey = Daylight.Umber,
    )

/** The accents that belong on this scheme's background. */
internal val ColorScheme.accents: AccentColors
    get() = if (background.luminance() > HALF) DaylightAccents else NazAccents

private const val HALF = 0.5f

/** The accents of the theme being drawn. */
internal val Accents: AccentColors
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.accents
