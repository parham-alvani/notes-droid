package me.parham1995.notes.feature.note

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The note's menu reaches its handlers.
 *
 * A menu item is exactly the kind of thing this app has shipped inert: it
 * compiles, it draws, it closes when tapped, and nothing happens. So each one
 * is tapped here and has to arrive somewhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NoteMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private var pins = 0
    private var shares = 0

    private fun menu(
        pinned: Boolean = false,
        enabled: Boolean = true,
    ) {
        compose.setContent {
            NoteMenu(
                enabled = enabled,
                pinned = pinned,
                onShare = { shares++ },
                onTogglePin = { pins++ },
            )
        }
    }

    @Test
    fun `pin reaches its handler, once`() {
        menu()

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Pin to home screen").performClick()

        assertThat(pins).isEqualTo(1)
        assertThat(shares).isEqualTo(0)
    }

    @Test
    fun `a pinned note offers to unpin instead`() {
        menu(pinned = true)

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Pin to home screen").assertDoesNotExist()
        compose.onNodeWithText("Unpin from home screen").performClick()

        assertThat(pins).isEqualTo(1)
    }

    @Test
    fun `share is still there, and still shares`() {
        menu()

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Share").performClick()

        assertThat(shares).isEqualTo(1)
        assertThat(pins).isEqualTo(0)
    }

    @Test
    fun `nothing to act on until the note has loaded`() {
        menu(enabled = false)

        compose.onNodeWithContentDescription("More").assertIsNotEnabled()
    }
}
