package me.parham1995.notes.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import me.parham1995.notes.data.LineWidth
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
 * The padding that holds a note's column to [width] in a window [available]
 * wide: at least the usual margin, and whatever is left over split between the
 * two sides so the column sits in the middle.
 *
 * Padding rather than a narrower list, so the margins still scroll the note:
 * a list cut to 680dp in the middle of a tablet leaves two strips down the
 * sides that a thumb lands on and nothing answers.
 */
fun readingPadding(
    available: Dp,
    width: LineWidth,
    margin: Dp = READING_MARGIN,
): PaddingValues {
    val column = width.maxDp?.dp ?: available
    val side = maxOf(margin, (available - column) / 2)
    return PaddingValues(horizontal = side, vertical = margin)
}

private val READING_MARGIN = 16.dp

/**
 * The room a wide block -- a table, code, a diagram, display math, a picture
 * alone on its line -- may take past the reading column, in dp: `wide-blocks.md`
 * in notes-linux's `docs/specs/`, with [measure] the column (the line width),
 * [pane] the window it sits in, [side] the margin kept at each side and [limit]
 * `appearance.block_width` em at the text size.
 */
@Immutable
data class WideRoom(
    val measure: Dp,
    val pane: Dp,
    val side: Dp,
    val limit: Dp,
)

/**
 * The room past the column [width] in a window [available] wide, with blocks
 * allowed [blockWidth] em of [em]. Null when there is no column to grow past
 * (the whole window) or the setting is off; a window with no room beside the
 * column (a phone held upright) gets a room that [me.parham1995.notes.markdown.WideBlock.place]
 * turns down for every block.
 */
fun wideRoom(
    available: Dp,
    width: LineWidth,
    blockWidth: Int,
    em: Dp,
    margin: Dp = READING_MARGIN,
): WideRoom? {
    val measure = width.maxDp?.dp ?: return null
    if (blockWidth <= 0) return null
    return WideRoom(measure = measure, pane = available, side = margin, limit = em * blockWidth)
}

/** The page's [WideRoom], for a block that grows to read: a figure keeps its caption in the column. */
val LocalWideRoom = staticCompositionLocalOf<WideRoom?> { null }

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
