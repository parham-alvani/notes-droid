package me.parham1995.notes.ui.image

import android.animation.ValueAnimator
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.DataSource
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.request.Options
import com.google.common.truth.Truth.assertThat
import okio.Buffer
import okio.FileSystem
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which decoder an animated picture gets: the one that plays it, or -- with
 * the system's animations off -- the one that draws its first frame.
 *
 * `coil-gif` installs its animated decoder by itself, so a mistake here does
 * not fail loudly: it plays the GIF for someone who asked for nothing on
 * their screen to move.
 */
@RunWith(RobolectricTestRunner::class)
class VaultAnimatedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val loader = ImageLoader(context)

    @After
    fun animationsBackOn() = durationScale(1f)

    /** The animator duration scale "Remove animations" sets to 0; a hidden, test-only setter. */
    private fun durationScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun decoderFor(header: ByteArray): Decoder? {
        val source = ImageSource(Buffer().write(header), FileSystem.SYSTEM)
        return VaultAnimated.factory.create(SourceFetchResult(source, null, DataSource.DISK), Options(context), loader)
    }

    @Test
    fun `a gif plays`() {
        assertThat(decoderFor(GIF)).isInstanceOf(AnimatedImageDecoder::class.java)
    }

    @Test
    fun `with animations removed a gif is drawn still`() {
        durationScale(0f)
        assertThat(ValueAnimator.areAnimatorsEnabled()).isFalse()
        assertThat(decoderFor(GIF)).isInstanceOf(BitmapFactoryDecoder::class.java)
    }

    @Test
    fun `anything that cannot move is left to the decoders after this one`() {
        assertThat(decoderFor(PNG)).isNull()
        durationScale(0f)
        assertThat(decoderFor(PNG)).isNull()
    }

    private companion object {
        /** Only the headers: the decoders choose by the first bytes, and none is run. */
        val GIF = "GIF89a".toByteArray()
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    }
}
