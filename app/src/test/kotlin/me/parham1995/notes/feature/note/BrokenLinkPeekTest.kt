package me.parham1995.notes.feature.note

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A link to nothing offers to make the note it names, only where the vault can be written. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BrokenLinkPeekTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the create button reaches its handler`() {
        var created = 0
        compose.setContent { BrokenLinkPeek(target = "Plan", onDismiss = {}, onCreate = { created++ }) }

        compose.onNodeWithText("Create this note").performClick()

        assertThat(created).isEqualTo(1)
    }

    @Test
    fun `a vault that cannot be written offers no button`() {
        compose.setContent { BrokenLinkPeek(target = "Plan", onDismiss = {}) }

        compose.onNodeWithText("Plan").assertExists()
        compose.onNodeWithText("Create this note").assertDoesNotExist()
    }
}
