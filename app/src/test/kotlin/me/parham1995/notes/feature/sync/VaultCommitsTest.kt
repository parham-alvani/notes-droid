package me.parham1995.notes.feature.sync

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.database.VaultEntity
import org.junit.Test

/**
 * That the status card says which commit each vault is at.
 *
 * It showed the sync journal's one commit -- whichever vault's sync finished
 * first -- so with two vaults it described one of them and called it the state
 * of both.
 */
class VaultCommitsTest {
    @Test
    fun `each vault shows its own commit`() {
        val vaults =
            listOf(
                VaultEntity(id = 1, owner = "someone", repo = "notes", headCommit = "aaaaaaa1"),
                VaultEntity(id = 2, owner = "someone", repo = "journal", headCommit = null),
            )
        assertThat(vaultCommits(vaults)).containsExactly("notes" to "aaaaaaa1", "journal" to null).inOrder()
    }

    @Test
    fun `one vault needs no name`() {
        val vaults = listOf(VaultEntity(id = 1, owner = "someone", repo = "notes", headCommit = "bbbbbbb2"))
        assertThat(vaultCommits(vaults)).containsExactly(null to "bbbbbbb2")
    }
}
