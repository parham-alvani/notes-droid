package me.parham1995.notes.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * naz, soft: a colour of naz with its strength capped, for the dark theme's
 * second palette (`appearance.dark_palette = "naz-soft"`, `look.md` in
 * notes-linux's `docs/specs/`).
 *
 * The hue stays; the OKLCH chroma is at most 0.095 (0.14 for the reds, which
 * would turn to pink) and the lightness at most 0.90. The neutrals are a
 * table. The same rule as `SoftPalette.swift` and `soft_palette.rs`, and the
 * same values as the desktops' reader page.
 */
internal object SoftPalette {
    private val NEUTRALS =
        mapOf(
            0x323232 to 0x2F2F2D,
            0x3A3A35 to 0x383835,
            0x282826 to 0x272725,
            0xF5F5F0 to 0xE2E2DD,
            0x4A4A4A to 0x474744,
        )
    private val REDS = setOf(0xFF5070, 0xFF7088)

    private const val CAP = 0.095
    private const val RED_CAP = 0.14
    private const val LIGHTEST = 0.90

    /** [rgb] (`0xRRGGBB`) as naz, soft has it. */
    fun soften(rgb: Int): Int {
        NEUTRALS[rgb]?.let { return it }
        val (l, a, b) = oklab(rgb)
        val chroma = hypot(a, b)
        val cap = if (rgb in REDS) RED_CAP else CAP
        val scale = if (chroma > cap) cap / chroma else 1.0
        return srgb(minOf(l, LIGHTEST), a * scale, b * scale)
    }

    @Suppress("MagicNumber") // the OKLab matrices
    private fun oklab(rgb: Int): Triple<Double, Double, Double> {
        fun linear(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(16)
        val g = linear(8)
        val b = linear(0)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Triple(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    @Suppress("MagicNumber") // the OKLab matrices
    private fun srgb(
        l: Double,
        a: Double,
        b: Double,
    ): Int {
        val l3 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m3 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s3 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)

        fun byte(x: Double): Int {
            val c = if (x <= 0.0031308) 12.92 * x else 1.055 * maxOf(x, 0.0).pow(1 / 2.4) - 0.055
            return (255 * c).roundToInt().coerceIn(0, 255)
        }
        val r = byte(4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3)
        val g = byte(-1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3)
        val blue = byte(-0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3)
        return (r shl 16) or (g shl 8) or blue
    }
}

private const val RGB = 0xFFFFFF
private const val OPAQUE = 0xFF000000L

/** This colour of naz as naz, soft has it; its alpha stays. */
internal fun Color.soft(): Color = Color(OPAQUE or SoftPalette.soften(toArgb() and RGB).toLong()).copy(alpha = alpha)

/** The background of naz, soft: how a scheme is known to be it. */
internal val SoftBlack = Naz.Black.soft()
