package me.parham1995.notes.ui.image

import android.graphics.Canvas
import android.graphics.Picture
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import coil3.Extras
import coil3.Image
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

    /**
     * Whether to keep the SVG as paths, drawn when the picture is drawn,
     * rather than rendered once into a bitmap. For the viewer: zoomed six
     * times, a bitmap the size of the screen is a blur, while paths drawn
     * under the zoom's scale are as sharp at 6x as at 1x. On the page the
     * bitmap is the better trade, since it is drawn on every frame of a
     * scroll and is never larger than it is shown.
     */
    val VECTOR = Extras.Key(default = false)

    val factory: Decoder.Factory =
        object : Decoder.Factory {
            // Indexed by (dark, vector), each with its own parser and output.
            private val factories =
                listOf(false, true).associateWith { dark ->
                    listOf(false, true).associateWith { vector ->
                        SvgDecoder.Factory(parser = parser(dark, vector), renderToBitmap = !vector)
                    }
                }

            override fun create(
                result: SourceFetchResult,
                options: Options,
                imageLoader: ImageLoader,
            ): Decoder? =
                factories
                    .getValue(options.getExtra(DARK))
                    .getValue(options.getExtra(VECTOR))
                    .create(result, options, imageLoader)
        }

    private fun parser(
        dark: Boolean,
        vector: Boolean,
    ): Svg.Parser =
        object : Svg.Parser {
            override fun parse(source: BufferedSource): Svg {
                val svg = Svg.Parser.DEFAULT.parse(Buffer().writeUtf8(SvgCompat.rewrite(source.readUtf8(), dark)))
                return if (vector) Recorded(svg) else svg
            }
        }

    /**
     * An SVG whose paths are recorded once, at the size Coil decoded it for,
     * and played back wherever it is drawn.
     *
     * Coil's own image for an SVG kept as paths renders straight into the
     * canvas it is handed, and AndroidSVG fits the drawing to that canvas's
     * width and height -- right for the bitmap Coil makes of it, wrong for a
     * canvas that is the whole viewer, which the drawing is then fitted to a
     * second time and offset from. In a test it drew nothing at all. Recording
     * into a picture of the picture's own size gives AndroidSVG the canvas it
     * expects, and a played-back picture is still paths: the zoom draws them
     * again at its own scale.
     */
    private class Recorded(
        private val svg: Svg,
    ) : Svg by svg {
        override fun asImage(
            width: Int,
            height: Int,
        ): Image = Played(svg.asImage(width, height))
    }

    private class Played(
        private val image: Image,
    ) : Image by image {
        // Recorded here, on the decoder's thread, rather than on the first
        // frame: AndroidSVG walking a large diagram is the slow part.
        private val picture =
            Picture().apply {
                image.draw(beginRecording(image.width, image.height))
                endRecording()
            }

        override fun draw(canvas: Canvas) = canvas.drawPicture(picture)
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

/**
 * Keeps an SVG as paths rather than a bitmap, so it stays sharp however far it
 * is zoomed. See [VaultSvg.VECTOR]. Part of the cache key, so the page's
 * bitmap and the viewer's paths are not mistaken for each other.
 */
fun ImageRequest.Builder.asVector(): ImageRequest.Builder =
    apply {
        extras[VaultSvg.VECTOR] = true
        memoryCacheKeyExtra("vector", "true")
    }

private const val HALF = 0.5f
