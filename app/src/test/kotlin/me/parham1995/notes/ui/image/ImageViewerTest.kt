package me.parham1995.notes.ui.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The image viewer's frame and its zoom.
 *
 * The frame is what the move to Telephoto could quietly have lost: the way
 * out, the way to another app, the caption, and the black that keeps a tap
 * from falling through to the page under the layer. The zoom is the one
 * decision made here rather than in Telephoto -- a double tap zooms in on the
 * point tapped, and the next one goes back to fitted.
 *
 * Pinch and fling are Telephoto's and are not repeated here; they need two
 * fingers on a phone to be believed anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageViewerTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private var closed = 0
    private var handedOn = 0
    private var pageTaps = 0

    /** A synthetic picture: two halves of a colour each, wider than tall. */
    private fun picture(): File {
        val bitmap = Bitmap.createBitmap(PICTURE_WIDTH, PICTURE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            clipRect(0, 0, PICTURE_WIDTH / 2, PICTURE_HEIGHT)
            drawColor(Color.YELLOW)
        }
        return folder.newFile("synthetic.png").apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** The viewer as the note screen shows it: a layer over a page that counts its taps. */
    private fun overPage(
        model: Any?,
        caption: String? = null,
        unavailable: String? = null,
    ) {
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("page").clickable { pageTaps++ }) {
                ZoomableContent(
                    model = model,
                    unavailable = unavailable,
                    caption = caption,
                    contentDescription = "a synthetic picture",
                    onDismiss = { closed++ },
                    onOpenExternally = { handedOn++ },
                )
            }
        }
    }

    /**
     * Coil decodes on a thread of its own, and the test's clock does not wait
     * for it: `waitUntil` alone timed out with the picture decoded and waiting
     * for a frame. Real time for the decode, then a frame for the result.
     */
    private fun awaitPicture(zoom: ZoomableImageState) {
        val deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS
        while (!zoom.isSettled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertThat(zoom.isSettled()).isTrue()
    }

    @Test
    fun `the close button closes and the other hands the picture on`() {
        overPage(model = picture())

        compose.onNodeWithContentDescription("Close").performClick()
        assertThat(closed).isEqualTo(1)
        assertThat(handedOn).isEqualTo(0)

        compose.onNodeWithContentDescription("Open in another app").performClick()
        assertThat(handedOn).isEqualTo(1)
    }

    @Test
    fun `the caption is shown under the picture`() {
        overPage(model = picture(), caption = "The synthetic figure")
        compose.onNodeWithText("The synthetic figure").assertExists()
    }

    @Test
    fun `a picture that is not coming says so`() {
        overPage(model = null, unavailable = "missing.png is not available")
        compose.onNodeWithText("missing.png is not available").assertExists()
    }

    @Test
    fun `a tap on the viewer does not reach the page under it`() {
        overPage(model = picture())
        compose.waitForIdle()

        compose.onNodeWithTag("page").performTouchInput {
            click(center)
            // The black below a wide picture, where nothing is drawn.
            click(Offset(centerX, height - 40f))
        }

        assertThat(pageTaps).isEqualTo(0)
    }

    @Test
    fun `a double tap zooms in on the point tapped, and the next goes back to fitted`() {
        val file = picture()
        lateinit var zoom: ZoomableImageState
        compose.setContent {
            zoom = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 6f)))
            ZoomablePicture(
                model = file,
                contentDescription = "a synthetic picture",
                modifier = Modifier.testTag("picture"),
                zoom = zoom,
            )
        }
        awaitPicture(zoom)
        // Fitted across the width, being twice as wide as tall. Worked out
        // rather than read from the zoom, which in a full run was read once
        // as the 1 it starts from.
        val fitted =
            compose
                .onNodeWithTag("picture")
                .fetchSemanticsNode()
                .size.width / PICTURE_WIDTH.toFloat()

        fun doubleTapAt(x: (Float) -> Float) {
            compose.onNodeWithTag("picture").performTouchInput { doubleClick(Offset(x(width.toFloat()), centerY)) }
            compose.waitForIdle()
        }

        // Left of centre, then in the middle. What was under the finger stays
        // near it, so the first leaves the picture further right than the
        // second; a zoom about the middle whatever was tapped, as the viewer
        // used to do, would leave both in the same place.
        doubleTapAt { it / 4f }
        val atTheLeft = zoom.zoomableState.contentTransformation
        assertThat(atTheLeft.scale.scaleX).isWithin(SCALE_TOLERANCE).of(DOUBLE_TAP_ZOOM * fitted)

        doubleTapAt { it / 2f }
        assertThat(zoom.zoomableState.zoomFraction).isEqualTo(0f)

        doubleTapAt { it / 2f }
        val inTheMiddle = zoom.zoomableState.contentTransformation
        assertThat(inTheMiddle.scale.scaleX).isWithin(SCALE_TOLERANCE).of(atTheLeft.scale.scaleX)
        assertThat(atTheLeft.offset.x).isGreaterThan(inTheMiddle.offset.x)
    }

    @Test
    fun `a double tap after a small pinch goes back to fitted, not further in`() {
        val file = picture()
        lateinit var zoom: ZoomableImageState
        lateinit var scope: CoroutineScope
        compose.setContent {
            scope = rememberCoroutineScope()
            zoom = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 6f)))
            ZoomablePicture(
                model = file,
                contentDescription = "a synthetic picture",
                modifier = Modifier.testTag("picture"),
                zoom = zoom,
            )
        }
        awaitPicture(zoom)

        // What a pinch that only just opened leaves behind: 1.2 times.
        compose.runOnIdle { scope.launch { zoom.zoomableState.zoomBy(zoomFactor = 1.2f) } }
        compose.waitForIdle()
        assertThat(zoom.zoomableState.zoomFraction).isGreaterThan(0f)

        compose.onNodeWithTag("picture").performTouchInput { doubleClick(center) }
        compose.waitForIdle()

        assertThat(zoom.zoomableState.zoomFraction).isEqualTo(0f)
    }

    /**
     * Drawn, and placed: the picture can be on screen a frame before the zoom
     * has measured it, and a scale read then is the 1 it starts from.
     */
    private fun ZoomableImageState.isSettled(): Boolean =
        isImageDisplayed && zoomableState.zoomFraction != null && zoomableState.contentTransformation.isSpecified

    private companion object {
        const val PICTURE_WIDTH = 1600
        const val PICTURE_HEIGHT = 800
        const val LOAD_TIMEOUT_MS = 5_000L
        const val POLL_MS = 50L
        const val SCALE_TOLERANCE = 0.01f

        /** What the viewer zooms to on a double tap, times fitted. */
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
