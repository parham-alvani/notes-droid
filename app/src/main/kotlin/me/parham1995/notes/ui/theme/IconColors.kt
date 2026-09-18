package me.parham1995.notes.ui.theme

import androidx.compose.ui.graphics.Color
import me.parham1995.notes.icons.IconColor

/**
 * Iconic's colour names, in naz.
 *
 * The plugin stores a name rather than a value, which is the useful half of the
 * bargain: the same assignment can read correctly against Obsidian's light
 * theme on a laptop and against this app's naz background on a phone. A colour
 * picked from the RGB wheel instead has no such freedom and is used verbatim.
 */
internal fun IconColor?.resolve(fallback: Color): Color =
    when (this) {
        null -> fallback
        is IconColor.Rgb -> Color(0xFF000000L or (value.toLong() and 0xFFFFFFL))
        is IconColor.Named ->
            when (name.lowercase()) {
                "red" -> Naz.Red
                "orange" -> Naz.Orange
                "yellow" -> Naz.Yellow
                "green" -> Naz.SpringGreen
                "mint" -> Naz.LimeGreen
                "cyan" -> Naz.Aqua
                "blue" -> Naz.Blue
                "purple" -> Naz.Purple
                "pink" -> Naz.Pink
                "gray", "grey" -> Naz.Grey
                // A colour this app has not been taught is better shown in the
                // ordinary tint than guessed at.
                else -> fallback
            }
    }
