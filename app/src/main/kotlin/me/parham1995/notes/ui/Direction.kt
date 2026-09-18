package me.parham1995.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import me.parham1995.notes.markdown.MdDirection
import me.parham1995.notes.markdown.TextDirection

/**
 * Lays text out the way it reads.
 *
 * The renderer already does this per block, which is the hard half and the one
 * that matters most. Everywhere else in the app shows lines taken *out* of
 * notes -- a task, a search excerpt, the line a backlink came from -- and those
 * were all laid out left to right regardless of what they said. Of this vault's
 * 2,407 notes, 112 contain Persian and 26 open tasks are written in it.
 *
 * Compose already orders the glyphs within a string correctly; what it cannot
 * guess is the paragraph's base direction, which decides which edge the line
 * starts at and where a trailing full stop lands. That comes from the first
 * strong character, the same rule the renderer uses -- so a Persian sentence
 * with an English word in it stays Persian, rather than flipping on the word.
 */
@Composable
fun AutoDirection(
    text: String,
    content: @Composable () -> Unit,
) {
    val direction =
        when (TextDirection.of(text)) {
            MdDirection.RTL -> LayoutDirection.Rtl
            MdDirection.LTR -> LayoutDirection.Ltr
        }
    CompositionLocalProvider(LocalLayoutDirection provides direction, content = content)
}
