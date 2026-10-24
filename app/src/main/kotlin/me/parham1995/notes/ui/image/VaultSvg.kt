package me.parham1995.notes.ui.image

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import coil3.Extras
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.svg.Svg
import coil3.svg.SvgDecoder
import me.parham1995.notes.markdown.SvgCompat
import okio.Buffer
import okio.BufferedSource

/**
 * Coil's SVG decoder, handed each file through [SvgCompat] first.
 *
 * Registered ahead of the decoder `coil-svg` installs by itself, so every SVG
 * the app draws comes through here: a vault image, the viewer, a mermaid
 * diagram. Without it, a figure with an SVG 2 arrowhead was a caption and an
 * empty space.
 *
 * `Svg.Parser` is marked experimental in Coil 3.6; it is the only way to see
 * the file's text before AndroidSVG does, so a Coil update may need this file
 * touched.
 */
@OptIn(ExperimentalCoilApi::class)
object VaultSvg {
    /**
     * Whether the page under the picture is dark, which decides the SVG's own
     * `prefers-color-scheme` rules. The app's theme, not the system's: the
     * two differ whenever the theme is set by hand.
     */
    val DARK = Extras.Key(default = false)

    val factory: Decoder.Factory =
        object : Decoder.Factory {
            private val light = SvgDecoder.Factory(parser = parser(dark = false))
            private val dark = SvgDecoder.Factory(parser = parser(dark = true))

            override fun create(
                result: SourceFetchResult,
                options: Options,
                imageLoader: ImageLoader,
            ): Decoder? = (if (options.getExtra(DARK)) dark else light).create(result, options, imageLoader)
        }

    private fun parser(dark: Boolean): Svg.Parser =
        object : Svg.Parser {
            override fun parse(source: BufferedSource): Svg =
                Svg.Parser.DEFAULT.parse(Buffer().writeUtf8(SvgCompat.rewrite(source.readUtf8(), dark)))
        }
}

/** Whether the page is dark, as the theme painted it. */
@Composable
fun pageIsDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < HALF

/**
 * Draws an SVG for a page that is [dark] or light. Part of the cache key too,
 * or switching the theme would keep showing the picture drawn for the old one.
 */
fun ImageRequest.Builder.onPage(dark: Boolean): ImageRequest.Builder =
    apply {
        extras[VaultSvg.DARK] = dark
        memoryCacheKeyExtra("dark", dark.toString())
    }

private const val HALF = 0.5f
