package me.parham1995.notes.feature.sync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.CloneSettings
import me.parham1995.notes.data.FromVault
import me.parham1995.notes.data.VaultOverlay
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.feature.note.NoteMenu
import me.parham1995.notes.ui.ReadOnlyBadge
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A read-only vault and the vault's settings, where they are shown
 * (`read-only-vault.md` section 4 and `vault-config.md` section 4 in
 * notes-linux's `docs/specs/`): each switch reaches its handler, and what is
 * said to be off is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class VaultGitSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val vault =
        VaultEntity(id = 1, owner = "someone", repo = "vault", name = "vault", transport = "SSH", headCommit = "abc")

    @Test
    fun `the read-only switch and the address reach their handlers`() {
        val asked = mutableListOf<Boolean>()
        val addresses = mutableListOf<String>()
        compose.setContent {
            VaultGitCard(
                vault = vault.copy(canWrite = true),
                clone = CloneSettings(readOnly = false, address = "git@github.com:someone/vault.git"),
                message = null,
                onReadOnly = { asked += it },
                onAddress = { addresses += it },
            )
        }

        compose.onNodeWithText("Read-only").assertIsOff().performClick()
        assertThat(asked).containsExactly(true)

        // Unchanged, there is nothing to save.
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("git@github.com:someone/vault.git").performTextReplacement("git@mirror:vault.git")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertThat(addresses).containsExactly("git@mirror:vault.git")
        // A key that can write is offered nothing more.
        compose.onNodeWithText("Make this vault read-only").assertDoesNotExist()
    }

    @Test
    fun `a key that cannot write is said so, with the offer to make the vault read-only`() {
        val asked = mutableListOf<Boolean>()
        compose.setContent {
            VaultGitCard(
                vault = vault.copy(canWrite = false),
                clone = CloneSettings(readOnly = false, address = "git@github.com:someone/vault.git"),
                message = null,
                onReadOnly = { asked += it },
                onAddress = {},
            )
        }

        compose.onNodeWithText("This key cannot write to the repository.").assertExists()
        compose.onNodeWithText("Make this vault read-only").performClick()

        assertThat(asked).containsExactly(true)
    }

    @Test
    fun `a read-only vault shows the switch on and offers nothing more`() {
        compose.setContent {
            VaultGitCard(
                vault = vault.copy(canWrite = false),
                clone = CloneSettings(readOnly = true, address = null),
                message = null,
                onReadOnly = {},
                onAddress = {},
            )
        }

        compose.onNodeWithText("Read-only").assertIsOn()
        compose.onNodeWithText("Make this vault read-only").assertDoesNotExist()
    }

    @Test
    fun `the vault's settings are declined all at once or one by one`() {
        val follows = mutableListOf<Boolean>()
        val declines = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            VaultSettingsCard(
                fromVault =
                    FromVault(
                        declined = setOf(VaultOverlay.TEXT_SIZE),
                        applied = setOf(VaultOverlay.THEME),
                        offered = setOf(VaultOverlay.THEME, VaultOverlay.TEXT_SIZE),
                    ),
                onFollow = { follows += it },
                onDecline = { key, declined -> declines += key to declined },
            )
        }

        compose.onNodeWithText("Follow the vault's settings").assertIsOn().performClick()
        assertThat(follows).containsExactly(false)

        // Each key the vault sets, by the name the setting has in this app and the key it has in the file.
        compose.onNodeWithText("Theme").assertIsOn().performClick()
        compose.onNodeWithText("Size").assertIsOff().performClick()
        assertThat(declines).containsExactly(VaultOverlay.THEME to true, VaultOverlay.TEXT_SIZE to false).inOrder()
        compose.onNodeWithText("appearance.theme").assertExists()
    }

    @Test
    fun `with the vault not followed, no key can be turned on by itself`() {
        compose.setContent {
            VaultSettingsCard(
                fromVault = FromVault(follow = false, offered = setOf(VaultOverlay.THEME)),
                onFollow = {},
                onDecline = { _, _ -> },
            )
        }

        compose.onNodeWithText("Follow the vault's settings").assertIsOff()
        compose.onNodeWithText("Theme").assertIsOff().assertIsNotEnabled()
    }

    @Test
    fun `a setting the vault sets says so, and another does not`() {
        compose.setContent {
            FromVaultNote(FromVault(applied = setOf(VaultOverlay.THEME)), VaultOverlay.THEME)
            FromVaultNote(FromVault(applied = setOf(VaultOverlay.THEME)), VaultOverlay.TEXT_SIZE)
        }

        compose.onNodeWithText("From the vault", substring = true).assertExists()
        assertThat(
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("From the vault", substring = true),
                ).fetchSemanticsNodes(),
        ).hasSize(1)
    }

    @Test
    fun `in a read-only vault the note's writes are shown and off, and sync is Pull`() {
        var writes = 0
        var syncs = 0
        compose.setContent {
            NoteMenu(
                enabled = true,
                pinned = false,
                onShare = {},
                onTogglePin = {},
                onSync = { syncs++ },
                onSortTasks = { writes++ },
                onAddTask = { writes++ },
                onProperties = { writes++ },
                writes = false,
                pullsOnly = true,
            )
        }

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Sync now").assertDoesNotExist()
        listOf("Add a task", "Sort tasks").forEach { compose.onNodeWithText(it).assertIsNotEnabled() }
        // Where a note's properties are read; what it writes is refused like any other write.
        compose.onNodeWithText("Properties").assertIsEnabled()
        compose.onNodeWithText("Pull").performClick()

        assertThat(syncs).isEqualTo(1)
        assertThat(writes).isEqualTo(0)
    }

    @Test
    fun `the badge says read-only, and the whole sentence to a screen reader`() {
        compose.setContent { ReadOnlyBadge() }

        compose.onNodeWithText("Read-only").assertExists()
        compose
            .onNodeWithContentDescription("This vault is read-only on this phone. Daftar pulls and never writes.")
            .assertExists()
    }
}
