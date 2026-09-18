package me.parham1995.notes.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette from `1995parham/naz.vim`, the colorscheme this vault is written
 * in.
 *
 * Values are taken verbatim from `colors/naz.lua` so the app and the editor
 * agree. Nothing here is invented or adjusted; where a Material role has no
 * obvious counterpart it reuses the nearest one naz already defines.
 */
internal object Naz {
    val White = Color(0xFFF5F5F0)
    val WhiteDarker = Color(0xFFD0D0D0)
    val Black = Color(0xFF323232)
    val LightBlack = Color(0xFF3A3A35)
    val DarkBlack = Color(0xFF282826)
    val Grey = Color(0xFFA8A8A0)
    val LightGrey = Color(0xFF707080)
    val DarkGrey = Color(0xFF4A4A4A)
    val WarmGrey = Color(0xFFB09070)

    val Yellow = Color(0xFFF0E890)
    val Orange = Color(0xFFFFB040)
    val Blue = Color(0xFF40D0FF)
    val Aqua = Color(0xFF80E5FF)
    val Pink = Color(0xFFFF69B4)
    val Red = Color(0xFFFF5070)
    val Chartreuse = Color(0xFFA0FF40)
    val Purple = Color(0xFFF0A0F0)
    val SpringGreen = Color(0xFF20FFA0)
    val LimeGreen = Color(0xFFB8FFD0)
    val VividYellow = Color(0xFFFFE040)
}

/**
 * The light scheme's palette.
 *
 * Not naz -- naz has no light variant. These are its accents adjusted to sit on
 * paper: the four that were chosen to glow against `#323232` are darkened until
 * they read on white, and the rest are left alone.
 */
internal object Daylight {
    val Paper = Color(0xFFFAF9F5)
    val Panel = Color(0xFFEFEDE6)
    val Ink = Color(0xFF2A2A28)
    val FadedInk = Color(0xFF6A6A62)
    val Rule = Color(0xFFD8D5CC)

    /** naz's own, legible either way. */
    val Orange = Color(0xFFC06000)
    val Red = Color(0xFFC01030)

    /** Darkened: the originals vanish on white. */
    val Blue = Color(0xFF0070A0)
    val Amber = Color(0xFF8A6A00)
    val Green = Color(0xFF0A7A48)
    val Teal = Color(0xFF00707A)
    val Purple = Color(0xFF8040A0)
}
