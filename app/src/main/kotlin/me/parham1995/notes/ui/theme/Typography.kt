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
 * levels, or a note reads as one undifferentiated block. The sizes sit a step
 * under Material's own -- 15sp body rather than 16 -- because a phone held at
 * reading distance fits more of a note that way without anything feeling small,
 * and the reader's text-size setting is there for anyone who wants it larger. Line heights sit near
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
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Bold,
                ),
            headlineSmall =
                headlineSmall.copy(
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                ),
            titleLarge =
                titleLarge.copy(
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            titleMedium =
                titleMedium.copy(
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            titleSmall =
                titleSmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            bodyLarge = body(bodyLarge, 15.sp, 23.sp),
            bodyMedium = body(bodyMedium, 14.sp, 21.sp),
            bodySmall = body(bodySmall, 12.sp, 18.sp),
            // Material's labels, a half step down to sit with the body above.
            labelLarge = labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
            labelMedium = labelMedium.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
            labelSmall = labelSmall.copy(fontSize = 10.5.sp, lineHeight = 15.sp),
        )
    }

private fun body(
    base: TextStyle,
    size: androidx.compose.ui.unit.TextUnit,
    height: androidx.compose.ui.unit.TextUnit,
) = base.copy(fontSize = size, lineHeight = height, lineHeightStyle = Reading)
