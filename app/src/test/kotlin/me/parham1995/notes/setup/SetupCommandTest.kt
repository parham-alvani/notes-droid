package me.parham1995.notes.setup

import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.data.SyncTransport
import org.junit.Test

/** The extras `am broadcast` carries, read the way a person types them. */
class SetupCommandTest {
    @Test
    fun `digest takes nothing`() {
        assertThat(SetupCommand.parse(mapOf("cmd" to "digest")).getOrThrow()).isEqualTo(SetupCommand.Digest)
    }

    @Test
    fun `no command is status`() {
        assertThat(SetupCommand.parse(emptyMap()).getOrThrow()).isEqualTo(SetupCommand.Status)
    }

    @Test
    fun `a vault needs an owner and a repository, and the rest is optional`() {
        val parsed = SetupCommand.parse(mapOf("cmd" to "add-vault", "owner" to " acme ", "repo" to "wiki")).getOrThrow()
        assertThat(parsed).isEqualTo(SetupCommand.AddVault("acme", "wiki", branch = null, name = "", transport = null))

        val failed = SetupCommand.parse(mapOf("cmd" to "add-vault", "owner" to "acme"))
        assertThat(failed.exceptionOrNull()?.message).contains("--es repo")
    }

    @Test
    fun `transport is spelled either way and nothing else`() {
        assertThat(
            SetupCommand.parse(mapOf("cmd" to "set-transport", "vault" to "wiki", "transport" to "SSH")).getOrThrow(),
        ).isEqualTo(SetupCommand.SetTransport("wiki", SyncTransport.SSH))
        assertThat(
            SetupCommand.parse(mapOf("cmd" to "set-transport", "vault" to "wiki", "transport" to "git")).isFailure,
        ).isTrue()
    }

    @Test
    fun `a key is reused unless replace is asked for`() {
        assertThat(SetupCommand.parse(mapOf("cmd" to "key", "vault" to "wiki")).getOrThrow())
            .isEqualTo(SetupCommand.Key("wiki", replace = false))
        assertThat(SetupCommand.parse(mapOf("cmd" to "key", "vault" to "wiki", "replace" to "true")).getOrThrow())
            .isEqualTo(SetupCommand.Key("wiki", replace = true))
    }

    @Test
    fun `an unknown command names the ones there are`() {
        val failed = SetupCommand.parse(mapOf("cmd" to "frobnicate"))
        assertThat(failed.exceptionOrNull()?.message).contains("add-vault")
    }
}
