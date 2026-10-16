package me.parham1995.notes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import me.parham1995.notes.data.ThemeChoice

/**
 * The app wears the vault's own colours.
 *
 * Roles follow the highlight groups naz assigns rather than being picked by
 * eye: `Directory` and `Identifier` are orange, so folders and links are
 * orange; `Title` and `Search` are yellow, so headings and highlights are
 * yellow; `Comment` is grey, so secondary text is grey.
 *
 * naz defines no light palette -- it sets `background = "dark"` and stops --
 * so the light scheme below is not naz and does not pretend to be. It keeps
 * naz's accents, which are what carry meaning here, and darkens the ones that
 * were chosen to glow on black and would otherwise be invisible on paper. It
 * exists because a phone gets read outdoors; dark stays the default.
 *
 * The containers -- a selected tab, a chip, a property pill, an error banner --
 * are the accent they belong to washed into the background, not a grey. A
 * plain `DarkGrey` behind everything made the chrome around a note read as
 * one flat panel; a container the colour of its role says which role it is.
 */
internal val NazScheme =
    darkColorScheme(
        // Normal
        background = Naz.Black,
        onBackground = Naz.White,
        surface = Naz.Black,
        onSurface = Naz.White,
        // Pmenu and code: the editor's darker panel
        surfaceVariant = Naz.DarkBlack,
        onSurfaceVariant = Naz.Grey,
        // Stepped, so a sheet over a menu over the page each sit visibly apart.
        surfaceContainer = Naz.LightBlack,
        surfaceContainerHigh = lerp(Naz.LightBlack, Naz.White, 0.04f),
        surfaceContainerHighest = lerp(Naz.LightBlack, Naz.White, 0.08f),
        surfaceContainerLow = Naz.DarkBlack,
        surfaceContainerLowest = Naz.DarkBlack,
        // Directory / Identifier: folders, links, anything to act on
        primary = Naz.Orange,
        onPrimary = Naz.Black,
        primaryContainer = Naz.Orange.wash(Naz.Black),
        onPrimaryContainer = Naz.Orange,
        // Function
        secondary = Naz.Blue,
        onSecondary = Naz.Black,
        secondaryContainer = Naz.Blue.wash(Naz.Black),
        onSecondaryContainer = Naz.Aqua,
        // Title / Search: headings, and the background behind ==highlight==
        tertiary = Naz.Yellow,
        onTertiary = Naz.Black,
        tertiaryContainer = Naz.Yellow,
        onTertiaryContainer = Naz.Black,
        // WarningMsg
        error = Naz.Red,
        onError = Naz.Black,
        errorContainer = Naz.Red.wash(Naz.Black),
        onErrorContainer = Naz.Red,
        // Visual
        outline = Naz.LightGrey,
        outlineVariant = Naz.DarkGrey,
        inverseSurface = Naz.White,
        inverseOnSurface = Naz.Black,
        inversePrimary = Naz.WarmGrey,
        scrim = Naz.DarkBlack,
    )

/**
 * naz's accents against paper.
 *
 * Four of them are re-picked rather than reused: yellow, chartreuse, spring
 * green and aqua are legible on `#323232` and disappear on white. The rest are
 * naz's own, because a heading being orange is the part worth keeping.
 */
internal val DaylightScheme =
    lightColorScheme(
        background = Daylight.Paper,
        onBackground = Daylight.Ink,
        surface = Daylight.Paper,
        onSurface = Daylight.Ink,
        surfaceVariant = Daylight.Panel,
        onSurfaceVariant = Daylight.FadedInk,
        surfaceContainer = Daylight.Panel,
        surfaceContainerHigh = Daylight.Panel,
        surfaceContainerHighest = Daylight.Panel,
        surfaceContainerLow = Daylight.Paper,
        surfaceContainerLowest = Daylight.Paper,
        primary = Daylight.Orange,
        onPrimary = Daylight.Paper,
        primaryContainer = Daylight.Orange.wash(Daylight.Paper, PAPER_WASH),
        onPrimaryContainer = Daylight.Orange,
        secondary = Daylight.Blue,
        onSecondary = Daylight.Paper,
        secondaryContainer = Daylight.Blue.wash(Daylight.Paper, PAPER_WASH),
        onSecondaryContainer = Daylight.Blue,
        tertiary = Daylight.Amber,
        onTertiary = Daylight.Paper,
        tertiaryContainer = Daylight.Amber,
        onTertiaryContainer = Daylight.Ink,
        error = Daylight.Red,
        onError = Daylight.Paper,
        errorContainer = Daylight.Red.wash(Daylight.Paper, PAPER_WASH),
        onErrorContainer = Daylight.Red,
        outline = Daylight.FadedInk,
        outlineVariant = Daylight.Rule,
        inverseSurface = Naz.Black,
        inverseOnSurface = Naz.White,
        inversePrimary = Daylight.Orange,
        scrim = Daylight.Rule,
    )

/**
 * An accent mixed into a background, for the container behind that accent's
 * own text. Enough to be read as the colour, little enough that the accent
 * drawn on top keeps its contrast.
 */
internal fun Color.wash(
    background: Color,
    amount: Float = INK_WASH,
): Color = lerp(background, this, amount)

private const val INK_WASH = 0.24f

/** Paper takes a tint more readily than dark grey does. */
private const val PAPER_WASH = 0.14f

/**
 * Rounder than Material's defaults: menus, sheets, dialogs and chips all take
 * their corners from here, and the softer ones are what a current Android app
 * looks like next to the 4dp corners of a few years ago.
 */
internal val NazShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

/** Whether this choice comes out dark, here and now. */
@Composable
fun ThemeChoice.isDark(): Boolean =
    when (this) {
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    }

@Composable
fun NotesTheme(
    theme: ThemeChoice = ThemeChoice.DARK,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (theme.isDark()) NazScheme else DaylightScheme,
        typography = NazTypography,
        shapes = NazShapes,
        content = content,
    )
}
