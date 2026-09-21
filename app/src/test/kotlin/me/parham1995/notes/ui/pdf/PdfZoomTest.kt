package me.parham1995.notes.ui.pdf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That a pinch zooms and a finger still scrolls.
 *
 * The PDF viewer puts a gesture detector over a scrolling list, which is the
 * arrangement where one of the two always ends up eating the other: a detector
 * that consumes everything leaves a document that cannot be scrolled, and one
 * that consumes nothing leaves a pinch the list reads as a drag. Watching the
 * initial pass and consuming only from the second finger is what keeps both,
 * and it is not something reading the code tells you -- it needs the events.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PdfZoomTest {
    @get:Rule
    val compose = createComposeRule()

    private var zoom = 1f
    private var centroid = Offset.Zero

    /** Held by the test, so where the list got to can be asked after the fact. */
    private val list = LazyListState()

    @Test
    fun `two fingers moving apart zoom in`() {
        compose.setContent { Surface() }

        compose.onNodeWithTag("pages").performTouchInput {
            pinch(
                start0 = Offset(centerX - 20f, centerY),
                end0 = Offset(centerX - 200f, centerY),
                start1 = Offset(centerX + 20f, centerY),
                end1 = Offset(centerX + 200f, centerY),
            )
        }

        assertThat(zoom).isGreaterThan(1.5f)
        assertThat(centroid.x).isGreaterThan(0f)
    }

    @Test
    fun `one finger still reaches the list underneath`() {
        compose.setContent { Surface() }

        compose.onNodeWithTag("pages").performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertThat(list.firstVisibleItemIndex).isGreaterThan(0)
        assertThat(zoom).isEqualTo(1f)
    }

    @androidx.compose.runtime.Composable
    private fun Surface() {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectPinch { at, factor ->
                        zoom *= factor
                        centroid = at
                    }
                },
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("pages"), state = list) {
                items(40) { index ->
                    Text("page $index", Modifier.height(120.dp))
                }
            }
        }
    }
}

/** The arithmetic that keeps the point under the fingers where it was. */
@RunWith(RobolectricTestRunner::class)
class AnchoredScrollTest {
    @Test
    fun `the point under the fingers does not move`() {
        // Doubling the width doubles everything to the left of x = 720, so the
        // scroll has to grow by exactly that much for 720 to still be 720.
        assertThat(anchoredScroll(scroll = 0, around = 720f, from = 1f, to = 2f)).isEqualTo(720)
        assertThat(anchoredScroll(scroll = 720, around = 720f, from = 2f, to = 4f)).isEqualTo(2160)
    }

    @Test
    fun `zooming out at the left edge does not ask for a negative scroll`() {
        assertThat(anchoredScroll(scroll = 0, around = 0f, from = 4f, to = 1f)).isEqualTo(0)
        assertThat(anchoredScroll(scroll = 10, around = 700f, from = 4f, to = 1f)).isEqualTo(0)
    }

    @Test
    fun `a page is never rendered past what one is worth holding`() {
        // A4 in portrait, on a phone that is 1,440 pixels across: two and a bit
        // times the window, and not the four times a 3x pinch would ask for.
        val a4 = budgetWidth(0.707f)
        assertThat(a4).isGreaterThan(2f * 1440f)
        assertThat(a4).isLessThan(3f * 1440f)
    }
}
