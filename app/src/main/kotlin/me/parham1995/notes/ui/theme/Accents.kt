package me.parham1995.notes.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import me.parham1995.notes.markdown.CalloutKind
import me.parham1995.notes.markdown.TaskTint

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

/** naz's accents with their strength capped, for naz, soft. */
internal val NazSoftAccents =
    AccentColors(
        red = Naz.Red.soft(),
        orange = Naz.Orange.soft(),
        yellow = Naz.Yellow.soft(),
        vividYellow = Naz.VividYellow.soft(),
        springGreen = Naz.SpringGreen.soft(),
        chartreuse = Naz.Chartreuse.soft(),
        limeGreen = Naz.LimeGreen.soft(),
        blue = Naz.Blue.soft(),
        aqua = Naz.Aqua.soft(),
        purple = Naz.Purple.soft(),
        pink = Naz.Pink.soft(),
        grey = Naz.Grey.soft(),
        warmGrey = Naz.WarmGrey.soft(),
    )

/** The accents that belong on this scheme's background. */
internal val ColorScheme.accents: AccentColors
    get() =
        when {
            background.luminance() > HALF -> DaylightAccents
            background == SoftBlack -> NazSoftAccents
            else -> NazAccents
        }

private const val HALF = 0.5f

/** The accents of the theme being drawn. */
internal val Accents: AccentColors
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.accents

/**
 * A colour per kind, from naz rather than from the Material roles.
 *
 * The Material scheme only carries three accents, so a warning and a tip came
 * out the same colour and everything else fell back to primary. The top five
 * kinds are 93% of the 1,302 callouts in this vault, so telling those apart at
 * a glance is most of what a callout is for.
 */
internal fun CalloutKind.accent(accents: AccentColors): Color =
    when (this) {
        CalloutKind.WARNING -> accents.orange
        CalloutKind.DANGER, CalloutKind.BUG, CalloutKind.FAILURE -> accents.red
        CalloutKind.SUCCESS -> accents.springGreen
        CalloutKind.TIP -> accents.chartreuse
        CalloutKind.TODO -> accents.vividYellow
        CalloutKind.QUESTION -> accents.yellow
        CalloutKind.ABSTRACT -> accents.limeGreen
        CalloutKind.EXAMPLE -> accents.purple
        CalloutKind.QUOTE -> accents.warmGrey
        CalloutKind.FIGURE -> accents.pink
        CalloutKind.READ -> accents.aqua
        CalloutKind.INFO, CalloutKind.NOTE -> accents.blue
    }

/** The accent a status's icon is drawn in, or null when it is a colour of the scheme: the list marker's, the done one, the accent. */
internal fun TaskTint.accent(accents: AccentColors): Color? =
    when (this) {
        TaskTint.BLUE -> accents.blue
        TaskTint.RED -> accents.red
        TaskTint.YELLOW -> accents.yellow
        TaskTint.VIVID_YELLOW -> accents.vividYellow
        TaskTint.WARM_GREY -> accents.warmGrey
        TaskTint.ORANGE -> accents.orange
        TaskTint.SPRING_GREEN -> accents.springGreen
        TaskTint.LIST_MARKER, TaskTint.DONE, TaskTint.ACCENT -> null
    }
