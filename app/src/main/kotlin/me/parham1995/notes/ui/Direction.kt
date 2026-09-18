package me.parham1995.notes.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import me.parham1995.notes.markdown.MdDirection
import me.parham1995.notes.markdown.TextDirection
import me.parham1995.notes.ui.theme.Vazirmatn

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
    val rtl = TextDirection.of(text) == MdDirection.RTL
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        // Carried on the text style rather than applied here, because most
        // callers pass a style of their own and a style parameter replaces the
        // local rather than merging with it. `inScript()` is how they pick it
        // back up.
        LocalTextStyle provides
            LocalTextStyle.current.copy(
                fontFamily = if (rtl && LocalReading.current.persianFont) Vazirmatn else null,
            ),
        content = content,
    )
}
