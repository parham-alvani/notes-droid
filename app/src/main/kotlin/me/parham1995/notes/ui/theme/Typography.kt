package me.parham1995.notes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Typography for reading prose rather than for a settings screen.
 *
 * Material's defaults are tuned for interface labels; long-form markdown needs
 * more room between lines and a heading scale with real separation between
 * levels, or a note reads as one undifferentiated block. Line heights sit near
 * 1.5x, which is the usual comfortable range for body text, and headings trim
 * the extra leading so they sit tight to the paragraph they introduce.
 */
private val Reading =
    LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

internal val NazTypography =
    Typography().run {
        copy(
            headlineMedium =
                headlineMedium.copy(
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                ),
            headlineSmall =
                headlineSmall.copy(
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                ),
            titleLarge =
                titleLarge.copy(
                    fontSize = 19.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            titleMedium =
                titleMedium.copy(
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            titleSmall =
                titleSmall.copy(
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            bodyLarge = body(bodyLarge, 16.sp, 25.sp),
            bodyMedium = body(bodyMedium, 15.sp, 23.sp),
            bodySmall = body(bodySmall, 13.sp, 19.sp),
        )
    }

private fun body(
    base: TextStyle,
    size: androidx.compose.ui.unit.TextUnit,
    height: androidx.compose.ui.unit.TextUnit,
) = base.copy(fontSize = size, lineHeight = height, lineHeightStyle = Reading)
