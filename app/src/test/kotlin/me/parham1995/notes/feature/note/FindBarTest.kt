package me.parham1995.notes.feature.note

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

/** The find bar's arrows reach the screen, and its count is the hit the page marks. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class FindBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the arrows step one hit on and one back`() {
        val steps = mutableListOf<Int>()
        compose.setContent {
            FindBar(
                query = "pond",
                matches = 3,
                at = 1,
                onQueryChange = {},
                onStep = { steps += it },
            )
        }

        compose.onNodeWithContentDescription("Next match").performClick()
        compose.onNodeWithContentDescription("Previous match").performClick()
        assertThat(steps).containsExactly(1, -1).inOrder()
    }

    @Test
    fun `the count says which hit is current`() {
        compose.setContent { FindBar(query = "pond", matches = 3, at = 1, onQueryChange = {}, onStep = {}) }

        compose.onNodeWithText("2/3", substring = true).assertExists()
    }
}
