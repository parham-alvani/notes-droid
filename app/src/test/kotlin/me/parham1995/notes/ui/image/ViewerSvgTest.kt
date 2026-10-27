package me.parham1995.notes.ui.image

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * An SVG in the viewer: in the page's colours, and sharp when zoomed.
 *
 * Both are things the move to Telephoto could have lost without a sound. The
 * page's theme travels in the request's extras, which Telephoto rebuilds the
 * request around; and an SVG drawn into a bitmap the size of the screen and
 * then zoomed six times is the blur this change set out to get rid of.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewerSvgTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    /** The app's loader has the vault's SVG decoder; the test application's would not. */
    @Before
    fun vaultLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(VaultSvg.factory) }.build())
    }

    @After
    fun plainLoader() = SingletonImageLoader.reset()

    /**
     * Two halves with one straight edge between them, in the middle. The left
     * half is red, or blue on a dark page -- the colour-scheme rule the vault's
     * figures carry.
     */
    private fun figure(): File =
        folder.newFile("figure.svg").apply {
            writeText(
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50" width="100" height="50">
                  <style>
                    .left { fill: #ff0000; }
                    .right { fill: #00ff00; }
                    @media (prefers-color-scheme: dark) { .left { fill: #0000ff; } }
                  </style>
                  <rect class="left" x="0" y="0" width="50" height="50"/>
                  <rect class="right" x="50" y="0" width="50" height="50"/>
                </svg>
                """.trimIndent(),
            )
        }

    private lateinit var zoom: ZoomableImageState
    private lateinit var scope: CoroutineScope

    private fun show(dark: Boolean) {
        val file = figure()
        compose.setContent {
            scope = rememberCoroutineScope()
            zoom = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 6f)))
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                ZoomablePicture(
                    model = file,
                    contentDescription = "a synthetic figure",
                    modifier = Modifier.testTag("picture"),
                    zoom = zoom,
                )
            }
        }
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (!zoom.isSettled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertThat(zoom.isSettled()).isTrue()
    }

    private fun pixels(): PixelMap = compose.onNodeWithTag("picture").captureToImage().toPixelMap()

    private fun PixelMap.leftOfCentre(): Color = this[width / 4, height / 2]

    @Test
    fun `on a light page the figure takes its light colours`() {
        show(dark = false)
        assertThat(pixels().leftOfCentre().closeTo(Color.Red)).isTrue()
    }

    @Test
    fun `on a dark page the figure takes its dark colours`() {
        show(dark = true)
        assertThat(pixels().leftOfCentre().closeTo(Color.Blue)).isTrue()
    }

    /**
     * Drawn into a bitmap the size of the screen, this edge smeared over ten
     * pixels; drawn as Coil's own path image, nothing was drawn at all.
     */
    @Test
    fun `zoomed six times, the edge in the middle is still an edge`() {
        show(dark = false)
        compose.runOnIdle { scope.launch { zoom.zoomableState.zoomBy(zoomFactor = 6f) } }
        compose.waitForIdle()

        // Across the middle row, count the pixels that are neither colour: the
        // smear a bitmap leaves when it is stretched. Drawn again at the zoom's
        // scale, the edge is a pixel or two of anti-aliasing; a bitmap the
        // size of the screen stretched six times smears it over several.
        val row = pixels().let { map -> (0 until map.width).map { map[it, map.height / 2] } }
        val smeared = row.count { !it.closeTo(Color.Red) && !it.closeTo(Color.Green) }
        assertThat(row.count { it.closeTo(Color.Red) }).isGreaterThan(0)
        assertThat(row.count { it.closeTo(Color.Green) }).isGreaterThan(0)
        assertThat(smeared).isAtMost(SHARP_EDGE_PIXELS)
    }

    private fun Color.closeTo(other: Color): Boolean =
        abs(red - other.red) < CLOSE && abs(green - other.green) < CLOSE && abs(blue - other.blue) < CLOSE

    /**
     * Drawn, and placed: the picture can be on screen a frame before the zoom
     * has measured it, and a scale read then is the 1 it starts from.
     */
    private fun ZoomableImageState.isSettled(): Boolean =
        isImageDisplayed && zoomableState.zoomFraction != null && zoomableState.contentTransformation.isSpecified

    private companion object {
        const val LOAD_TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
        const val CLOSE = 0.1f
        const val SHARP_EDGE_PIXELS = 2
    }
}
