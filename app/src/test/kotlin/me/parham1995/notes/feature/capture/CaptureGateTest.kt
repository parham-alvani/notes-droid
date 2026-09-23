package me.parham1995.notes.feature.capture

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.R
import me.parham1995.notes.data.VaultSettings
import me.parham1995.notes.data.WriteSettings
import me.parham1995.notes.data.database.VaultEntity
import org.junit.Test

/**
 * That quick capture is offered when the vault it writes to can be written.
 *
 * It asked whether *any* vault could take a push, so a read-only scratchpad
 * vault beside a writable one offered the field, took the typing, and refused
 * the save.
 */
class CaptureGateTest {
    private val notes = VaultEntity(id = 1, owner = "someone", repo = "notes")
    private val journal = VaultEntity(id = 2, owner = "someone", repo = "journal")
    private val vaults = listOf(notes, journal)

    private fun settings(
        scratchpadVault: Long = 0,
        active: Long = 0,
        author: Boolean = true,
    ) = VaultSettings(
        activeVaultId = active,
        write =
            WriteSettings(
                authorName = if (author) "A Person" else "",
                authorEmail = if (author) "person@example.com" else "",
                scratchpadVaultId = scratchpadVault,
            ),
    )

    @Test
    fun `a read-only scratchpad vault blocks capture even when another vault is writable`() {
        val gate = captureGate(settings(scratchpadVault = 2, active = 1), vaults, writable = setOf(1L))
        assertThat(gate.ready).isFalse()
        assertThat(gate.blocked).isEqualTo(R.string.capture_read_only)
        assertThat(gate.vault).isEqualTo(journal)
    }

    @Test
    fun `a writable scratchpad vault is ready whatever the active vault can do`() {
        val gate = captureGate(settings(scratchpadVault = 2, active = 1), vaults, writable = setOf(2L))
        assertThat(gate.ready).isTrue()
        assertThat(gate.vault).isEqualTo(journal)
    }

    @Test
    fun `with no scratchpad vault chosen the active vault is the one asked`() {
        val gate = captureGate(settings(active = 2), vaults, writable = setOf(1L))
        assertThat(gate.blocked).isEqualTo(R.string.capture_read_only)
        assertThat(gate.vault).isEqualTo(journal)
    }

    @Test
    fun `a scratchpad vault that was removed says so`() {
        val gate = captureGate(settings(scratchpadVault = 9, active = 1), vaults, writable = setOf(1L, 2L))
        assertThat(gate.blocked).isEqualTo(R.string.capture_no_vault)
    }

    @Test
    fun `no author blocks everything`() {
        val gate = captureGate(settings(author = false), vaults, writable = setOf(1L, 2L))
        assertThat(gate.blocked).isEqualTo(R.string.capture_needs_author)
    }

    @Test
    fun `a second share joins what is already in the field`() {
        assertThat(mergeShared("", "a link")).isEqualTo("a link")
        assertThat(mergeShared("a thought ", "a link")).isEqualTo("a thought\na link")
    }
}
