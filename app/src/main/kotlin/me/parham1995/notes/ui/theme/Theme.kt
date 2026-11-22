package me.parham1995.notes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
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
 *
 * Each colour goes through [c]: itself for naz, its soft one for naz, soft.
 */
private fun nazScheme(c: (Color) -> Color) =
    darkColorScheme(
        // Normal
        background = c(Naz.Black),
        onBackground = c(Naz.White),
        surface = c(Naz.Black),
        onSurface = c(Naz.White),
        // Pmenu and code: the editor's darker panel
        surfaceVariant = c(Naz.DarkBlack),
        onSurfaceVariant = c(Naz.Grey),
        // Stepped, so a sheet over a menu over the page each sit visibly apart.
        surfaceContainer = c(Naz.LightBlack),
        surfaceContainerHigh = lerp(c(Naz.LightBlack), c(Naz.White), 0.04f),
        surfaceContainerHighest = lerp(c(Naz.LightBlack), c(Naz.White), 0.08f),
        surfaceContainerLow = c(Naz.DarkBlack),
        surfaceContainerLowest = c(Naz.DarkBlack),
        // Directory / Identifier: folders, links, anything to act on
        primary = c(Naz.Orange),
        onPrimary = c(Naz.Black),
        primaryContainer = c(Naz.Orange).wash(c(Naz.Black)),
        onPrimaryContainer = c(Naz.Orange),
        // Function
        secondary = c(Naz.Blue),
        onSecondary = c(Naz.Black),
        secondaryContainer = c(Naz.Blue).wash(c(Naz.Black)),
        onSecondaryContainer = c(Naz.Aqua),
        // Title / Search: headings, and the background behind ==highlight==
        tertiary = c(Naz.Yellow),
        onTertiary = c(Naz.Black),
        tertiaryContainer = c(Naz.Yellow),
        onTertiaryContainer = c(Naz.Black),
        // WarningMsg, a shade lighter: naz's red is 4.0:1 on Black, and a
        // sync failure is a sentence at body size, not a highlight.
        error = c(Naz.ErrorText),
        onError = c(Naz.Black),
        errorContainer = c(Naz.Red).wash(c(Naz.Black)),
        onErrorContainer = c(Naz.ErrorText),
        // Visual
        outline = c(Naz.LightGrey),
        outlineVariant = c(Naz.DarkGrey),
        inverseSurface = c(Naz.White),
        inverseOnSurface = c(Naz.Black),
        inversePrimary = c(Naz.WarmGrey),
        scrim = c(Naz.DarkBlack),
    )

/** naz, each colour as it is. */
internal val NazScheme = nazScheme { it }

/** naz, soft: naz with its colour strength capped ([SoftPalette]). */
internal val NazSoftScheme = nazScheme { it.soft() }

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

/**
 * Material 3 Expressive: the same colours, type and shapes, with the
 * spring-based motion scheme. Components read their animation specs from it,
 * so sheets, menus, switches and indicators settle with one feel rather than
 * the older fixed-duration easing.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NotesTheme(
    theme: ThemeChoice = ThemeChoice.DARK,
    softDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme =
            when {
                !theme.isDark() -> DaylightScheme
                softDark -> NazSoftScheme
                else -> NazScheme
            },
        motionScheme = MotionScheme.expressive(),
        typography = NazTypography,
        shapes = NazShapes,
        content = content,
    )
}
