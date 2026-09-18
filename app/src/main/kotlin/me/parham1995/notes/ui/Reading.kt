package me.parham1995.notes.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import me.parham1995.notes.data.ReadingSettings

/**
 * How the person reading has asked for notes to be set.
 *
 * A composition local rather than a parameter because it applies to every piece
 * of note text in the app and none of the interface around it: the settings
 * screen does not grow when someone makes their notes larger.
 */
val LocalReading = staticCompositionLocalOf { ReadingSettings() }

/**
 * A style as the reader asked for it: their size, their line spacing, and the
 * font the surrounding script chose.
 *
 * `Text(style = …)` replaces `LocalTextStyle` outright rather than merging with
 * it, so a caller naming a typography role -- which is nearly all of them --
 * would otherwise discard both. This is the single place they pick it back up,
 * which is also what makes a text-size setting one line of work per call site
 * rather than a rewrite.
 */
@Composable
fun TextStyle.inScript(): TextStyle {
    val reading = LocalReading.current
    return copy(
        fontFamily = LocalTextStyle.current.fontFamily,
        fontSize = if (fontSize.isSpecified) fontSize * reading.textScale else fontSize,
        // Scaled by both, because line height is an absolute measure: leaving
        // it alone while the text grows closes the gaps until the lines touch.
        lineHeight =
            if (lineHeight.isSpecified) {
                lineHeight * reading.textScale * reading.lineSpacing
            } else {
                lineHeight
            },
    )
}
