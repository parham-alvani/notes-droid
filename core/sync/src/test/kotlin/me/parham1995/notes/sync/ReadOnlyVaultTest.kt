package me.parham1995.notes.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The parts of a read-only vault that need no repository
 * (`read-only-vault.md` in notes-linux's `docs/specs/`), by the spec's test
 * names where it has one.
 */
class ReadOnlyVaultTest {
    @Test
    fun aRefusedPushIsNamed() {
        assertThat(
            ReadOnlyVault.pushRefusedReadOnly(
                "ERROR: The key you are authenticating with has been marked as read only.",
            ),
        ).isTrue()
        assertThat(ReadOnlyVault.pushRefusedReadOnly("ERROR: Write access to repository not granted.")).isTrue()
        assertThat(ReadOnlyVault.pushRefusedReadOnly("git@github.com: Permission denied (publickey).")).isFalse()
        // What JGit says of the same refusal, having no stderr to pass on.
        assertThat(ReadOnlyVault.pushRefusedReadOnly("git@github.com:someone/vault.git: push not permitted")).isTrue()
    }

    @Test
    fun `a git boolean that is not one reads as read-only`() {
        assertThat(ReadOnlyVault.isReadOnly(null)).isFalse()
        listOf("true", "yes", "on", "1", "TRUE", " Yes ").forEach { assertThat(ReadOnlyVault.isReadOnly(it)).isTrue() }
        listOf("false", "no", "off", "0", "Off", "").forEach { assertThat(ReadOnlyVault.isReadOnly(it)).isFalse() }
        assertThat(ReadOnlyVault.isReadOnly("perhaps")).isTrue()
    }

    @Test
    fun `an address is trimmed and an empty one is refused`() {
        assertThat(
            ReadOnlyVault.address("  git@github.com:someone/vault.git \n"),
        ).isEqualTo("git@github.com:someone/vault.git")
        assertThat(ReadOnlyVault.address("")).isNull()
        assertThat(ReadOnlyVault.address("   ")).isNull()
    }
}
