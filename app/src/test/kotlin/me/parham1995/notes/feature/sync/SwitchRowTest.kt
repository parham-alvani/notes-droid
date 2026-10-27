package me.parham1995.notes.feature.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A setting's row is its switch.
 *
 * The rows used to put the handler on the switch alone, so a tap on the
 * setting's name -- where a finger goes first -- reached nothing, and TalkBack
 * stopped twice on every setting: once for the words, once for an unnamed
 * switch. Both are things that compile, render, and do nothing, which is the
 * kind of bug this app's Compose tests are for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SwitchRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a tap on the words toggles the setting`() {
        var stored = false
        compose.setContent {
            var checked by remember { mutableStateOf(false) }
            SwitchRow(
                title = "Keep the screen on",
                help = "While a note is open",
                checked = checked,
                onCheckedChange = {
                    checked = it
                    stored = it
                },
            )
        }

        compose.onNodeWithText("While a note is open").performClick()
        assertThat(stored).isTrue()
        compose.onNodeWithText("Keep the screen on").assertIsOn()

        compose.onNodeWithText("Keep the screen on").performClick()
        assertThat(stored).isFalse()
        compose.onNodeWithText("Keep the screen on").assertIsOff()
    }

    @Test
    fun `the row is the one switch TalkBack finds`() {
        compose.setContent {
            SwitchRow(title = "Wi-Fi only", checked = false, onCheckedChange = {})
        }

        // In the merged tree the words and the state are one node with the
        // switch role; a second node with the role would be the switch's own,
        // read as a separate stop with no name.
        val switches =
            compose
                .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                .fetchSemanticsNodes()
        assertThat(switches).hasSize(1)
        assertThat(switches.single().config[SemanticsProperties.Text].joinToString { it.text })
            .isEqualTo("Wi-Fi only")
    }

    @Test
    fun `a disabled row cannot be toggled from beside the switch`() {
        var changes = 0
        compose.setContent {
            SwitchRow(
                title = "Hide files for AI agents",
                checked = true,
                onCheckedChange = { changes++ },
                enabled = false,
            )
        }

        compose.onNodeWithText("Hide files for AI agents").assertIsNotEnabled().performClick()
        assertThat(changes).isEqualTo(0)
    }
}
