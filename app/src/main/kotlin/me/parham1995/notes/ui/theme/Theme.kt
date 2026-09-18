package me.parham1995.notes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app wears the vault's own colours.
 *
 * Roles follow the highlight groups naz assigns rather than being picked by
 * eye: `Directory` and `Identifier` are orange, so folders and links are
 * orange; `Title` and `Search` are yellow, so headings and highlights are
 * yellow; `Comment` is grey, so secondary text is grey.
 *
 * There is no light variant, and that is deliberate -- naz sets
 * `background = "dark"` and defines no light palette, so a light mode would
 * mean inventing colours it does not have. Better to be the editor's theme
 * than an approximation of it.
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

@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NazScheme, typography = NazTypography, content = content)
}
