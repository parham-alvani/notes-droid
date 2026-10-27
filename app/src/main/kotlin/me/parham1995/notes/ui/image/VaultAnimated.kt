package me.parham1995.notes.ui.image

import android.animation.ValueAnimator
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.Options

/**
 * Animated GIF and WebP: played, unless the system's animations are off.
 *
 * Without `coil-gif`, Coil's plain decoder read the first frame of an animated
 * GIF and stopped there, so a screen recording in a note was a still that
 * looked like a screenshot. `coil-gif` registers `AnimatedImageDecoder` -- the
 * platform's `ImageDecoder`, API 28, under this app's minSdk -- by itself
 * through a service loader, so the dependency alone makes them play. This
 * decoder is what stands in front of it.
 *
 * "Remove animations" in the accessibility settings is asked for by people for
 * whom movement on the page is a problem, not a preference. With it on, an
 * animated picture is handed to the still decoder here, before the animated
 * one is asked, and the first frame is drawn as before. Returning no decoder
 * would not do it: the next one along is the animated one. The setting is read
 * when a picture is decoded, so one already in the memory cache keeps playing,
 * or keeping still, until it is decoded again.
 */
object VaultAnimated {
    val factory: Decoder.Factory =
        object : Decoder.Factory {
            private val animated = AnimatedImageDecoder.Factory()
            private val still = BitmapFactoryDecoder.Factory()

            override fun create(
                result: SourceFetchResult,
                options: Options,
                imageLoader: ImageLoader,
            ): Decoder? {
                // Null for anything the animated decoder would not take -- a
                // PNG, a still WebP -- which leaves it to the decoders after
                // this one, exactly as without it.
                val decoder = animated.create(result, options, imageLoader) ?: return null
                return if (ValueAnimator.areAnimatorsEnabled()) {
                    decoder
                } else {
                    still.create(result, options, imageLoader)
                }
            }
        }
}
