package me.parham1995.notes.ui.render

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That two buttons in a top bar are two buttons.
 *
 * `TopAppBar` lays its navigation slot out as a box, and so does `Scaffold`
 * with its content: siblings put there directly are drawn one on top of the
 * other. It has cost this app twice -- a tab strip behind the text it labelled,
 * and a back arrow hidden underneath a drawer button for a whole release --
 * because it compiles, it renders, and nothing complains. Overlap is a
 * question about coordinates, so it takes a test that asks about coordinates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class TopBarLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `two navigation buttons sit side by side, not on top of each other`() {
        compose.setContent {
            TopAppBar(
                title = { Text("A note") },
                navigationIcon = {
                    androidx.compose.foundation.layout.Row {
                        IconButton(onClick = {}) { Icon(Icons.Filled.Add, contentDescription = "files") }
                        IconButton(onClick = {}) { Icon(Icons.Filled.Add, contentDescription = "back") }
                    }
                },
            )
        }

        val files = compose.onNodeWithContentDescription("files").fetchSemanticsNode()
        val back = compose.onNodeWithContentDescription("back").fetchSemanticsNode()

        assertThat(files.boundsInRoot.right).isAtMost(back.boundsInRoot.left)
    }

    @Test
    fun `the same two without a Row overlap, which is the trap this guards`() {
        compose.setContent {
            TopAppBar(
                title = { Text("A note") },
                navigationIcon = {
                    IconButton(onClick = {}) { Icon(Icons.Filled.Add, contentDescription = "files") }
                    IconButton(onClick = {}) { Icon(Icons.Filled.Add, contentDescription = "back") }
                },
            )
        }

        val files = compose.onNodeWithContentDescription("files").fetchSemanticsNode()
        val back = compose.onNodeWithContentDescription("back").fetchSemanticsNode()

        // Not an assertion about what the app does -- an assertion about what
        // the framework does, so that the test above means something.
        assertThat(files.boundsInRoot.left).isEqualTo(back.boundsInRoot.left)
    }
}
