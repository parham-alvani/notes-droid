package me.parham1995.notes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
 */
private val NazScheme =
    darkColorScheme(
        // Normal
        background = Naz.Black,
        onBackground = Naz.White,
        surface = Naz.Black,
        onSurface = Naz.White,
        // Pmenu and code: the editor's darker panel
        surfaceVariant = Naz.DarkBlack,
        onSurfaceVariant = Naz.Grey,
        surfaceContainer = Naz.LightBlack,
        surfaceContainerHigh = Naz.LightBlack,
        surfaceContainerHighest = Naz.LightBlack,
        surfaceContainerLow = Naz.DarkBlack,
        surfaceContainerLowest = Naz.DarkBlack,
        // Directory / Identifier: folders, links, anything to act on
        primary = Naz.Orange,
        onPrimary = Naz.Black,
        primaryContainer = Naz.DarkGrey,
        onPrimaryContainer = Naz.Orange,
        // Function
        secondary = Naz.Blue,
        onSecondary = Naz.Black,
        secondaryContainer = Naz.DarkGrey,
        onSecondaryContainer = Naz.Aqua,
        // Title / Search: headings, and the background behind ==highlight==
        tertiary = Naz.Yellow,
        onTertiary = Naz.Black,
        tertiaryContainer = Naz.Yellow,
        onTertiaryContainer = Naz.Black,
        // WarningMsg
        error = Naz.Red,
        onError = Naz.Black,
        errorContainer = Naz.DarkGrey,
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
private val DaylightScheme =
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
        primaryContainer = Daylight.Panel,
        onPrimaryContainer = Daylight.Orange,
        secondary = Daylight.Blue,
        onSecondary = Daylight.Paper,
        secondaryContainer = Daylight.Panel,
        onSecondaryContainer = Daylight.Blue,
        tertiary = Daylight.Amber,
        onTertiary = Daylight.Paper,
        tertiaryContainer = Daylight.Amber,
        onTertiaryContainer = Daylight.Ink,
        error = Daylight.Red,
        onError = Daylight.Paper,
        errorContainer = Daylight.Panel,
        onErrorContainer = Daylight.Red,
        outline = Daylight.FadedInk,
        outlineVariant = Daylight.Rule,
        inverseSurface = Naz.Black,
        inverseOnSurface = Naz.White,
        inversePrimary = Daylight.Orange,
        scrim = Daylight.Rule,
    )

@Composable
fun NotesTheme(
    theme: ThemeChoice = ThemeChoice.DARK,
    content: @Composable () -> Unit,
) {
    val dark =
        when (theme) {
            ThemeChoice.DARK -> true
            ThemeChoice.LIGHT -> false
            ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        }
    MaterialTheme(
        colorScheme = if (dark) NazScheme else DaylightScheme,
        typography = NazTypography,
        content = content,
    )
}
