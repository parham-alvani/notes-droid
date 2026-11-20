package me.parham1995.notes.feature.sync

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.FromVault
import me.parham1995.notes.data.VaultOverlay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keep on This Device and Follow the Vault, on a long press of a setting
 * (`vault-config.md` in notes-linux's `docs/specs/`, section 5): the hold
 * opens the menu and its item reaches its handler, and a tap is still the
 * setting's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class VaultKeyMenuTest {
    @get:Rule
    val compose = createComposeRule()

    private var kept = 0
    private var followed = 0

    private fun actions(
        declined: Boolean = false,
        offered: Boolean = true,
    ) = VaultKeyActions(VaultOverlay.THEME, declined, offered, onKeep = { kept++ }, onFollow = { followed++ })

    @Test
    fun `a hold on a switch offers to keep it on this device, and a tap still turns it`() {
        val turned = mutableListOf<Boolean>()
        compose.setContent {
            SwitchRow(
                title = "Keep the screen on",
                checked = false,
                onCheckedChange = { turned += it },
                vault = actions(),
            )
        }

        compose.onNodeWithText("Keep the screen on").assertIsOff().performClick()
        assertThat(turned).containsExactly(true)
        compose.onNodeWithText("Keep on This Device").assertDoesNotExist()

        compose.onNodeWithText("Keep the screen on").performTouchInput { longClick() }
        compose.onNodeWithText("Follow the Vault").assertDoesNotExist()
        compose.onNodeWithText("Keep on This Device").performClick()

        assertThat(kept).isEqualTo(1)
        assertThat(followed).isEqualTo(0)
        // The hold was not a tap as well.
        assertThat(turned).containsExactly(true)
    }

    @Test
    fun `a setting kept on this device offers to follow the vault again`() {
        compose.setContent {
            SwitchRow(
                title = "Keep the screen on",
                checked = true,
                onCheckedChange = {},
                vault = actions(declined = true),
            )
        }

        compose.onNodeWithText("Keep the screen on").assertIsOn().performTouchInput { longClick() }
        compose.onNodeWithText("Keep on This Device").assertDoesNotExist()
        compose.onNodeWithText("Follow the Vault").performClick()

        assertThat(followed).isEqualTo(1)
        assertThat(kept).isEqualTo(0)
    }

    @Test
    fun `the name above a row of choices opens the same menu`() {
        compose.setContent { SettingLabel("Theme", actions()) }

        compose.onNodeWithText("Theme").performTouchInput { longClick() }
        compose.onNodeWithText("Keep on This Device").performClick()

        assertThat(kept).isEqualTo(1)
    }

    @Test
    fun `with the vault's settings declined altogether there is nothing to choose`() {
        compose.setContent { SettingLabel("Theme", actions(offered = false)) }

        compose.onNodeWithText("Theme").performTouchInput { longClick() }

        compose.onNodeWithText("Keep on This Device").assertDoesNotExist()
        compose.onNodeWithText("Follow the Vault").assertDoesNotExist()
    }

    @Test
    fun `a setting the vault sets is locked only where the vault cannot be written`() {
        val applied = setOf(VaultOverlay.THEME)

        assertThat(FromVault(applied = applied, writable = false).locks(VaultOverlay.THEME)).isTrue()
        assertThat(FromVault(applied = applied, writable = true).locks(VaultOverlay.THEME)).isFalse()
        // One the vault does not set is never locked: it is the phone's own.
        assertThat(FromVault(applied = applied, writable = false).locks(VaultOverlay.TEXT_SIZE)).isFalse()
    }
}
