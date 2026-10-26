package me.parham1995.notes.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Whether the note being read has the whole screen. Held above the tabs'
 * frame, because the rail is the frame's to hide and the bars over the page
 * are the note's: one tap on the page reaches both.
 */
@Stable
class FullScreen {
    var on by mutableStateOf(false)
}

val LocalFullScreen = staticCompositionLocalOf { FullScreen() }

/**
 * Whether a window of [size] pixels is roomy enough to read full screen in: a
 * tablet, or a phone on its side. In a phone held upright the bars already
 * slide away as the note is read, and a tap that hid them would mostly be a
 * tap that missed a link.
 */
fun roomyForFullScreen(
    size: IntSize,
    density: Float,
): Boolean = size.width > size.height || Dp(size.width / density) >= ROOMY_WIDTH

private val ROOMY_WIDTH = 600.dp
