package me.parham1995.notes.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the tabs go, as coordinates: under the screen on a phone, beside it on
 * a wide window, and never over it. The content slot is a box, and a rail and
 * a screen emitted into it side by side are drawn one on top of the other --
 * which renders, compiles and takes every tap meant for the screen.
 */
@RunWith(RobolectricTestRunner::class)
class TabFrameTest {
    @get:Rule
    val compose = createComposeRule()

    private val tapped = mutableListOf<String>()

    private fun render(showTabs: Boolean = true) {
        compose.setContent {
            TabFrame(
                tabs =
                    listOf("Browse", "Search").map { label ->
                        TabEntry(label, icon = "search", selected = label == "Browse", onClick = { tapped += label })
                    },
                showTabs = showTabs,
                snackbar = SnackbarHostState(),
            ) { placement -> Box(placement.testTag("screen")) }
        }
    }

    private fun bounds(description: String) =
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun screen() = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a phone has the bar underneath the screen`() {
        render()
        assertThat(screen().bottom).isAtMost(bounds("Search").top)
        assertThat(screen().left).isEqualTo(0f)
    }

    @Test
    @Config(qualifiers = "w900dp-h600dp")
    fun `a wide window has the rail beside the screen`() {
        render()
        assertThat(bounds("Search").right).isAtMost(screen().left)
        assertThat(screen().left).isGreaterThan(0f)
    }

    @Test
    @Config(qualifiers = "w900dp-h600dp")
    fun `the rail's tabs are wired`() {
        render()
        compose.onNodeWithContentDescription("Search", useUnmergedTree = true).performClick()
        assertThat(tapped).containsExactly("Search")
    }

    @Test
    @Config(qualifiers = "w900dp-h600dp")
    fun `without tabs the screen has the whole window`() {
        render(showTabs = false)
        compose.onNodeWithContentDescription("Search", useUnmergedTree = true).assertDoesNotExist()
        assertThat(screen().left).isEqualTo(0f)
    }
}
