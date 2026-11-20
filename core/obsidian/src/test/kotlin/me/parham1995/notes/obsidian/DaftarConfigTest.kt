package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the vault's `.daftar.toml` says about how its notes open
 * (`folded-sections.md` in notes-linux's `docs/specs/`).
 */
class DaftarConfigTest {
    @Test
    fun `the vault says whether its notes open folded`() {
        assertThat(DaftarConfig().folded).isFalse()
        assertThat(DaftarConfig.parse("").folded).isFalse()
        assertThat(DaftarConfig.parse("[reader]\nfolded = true\n").folded).isTrue()
        assertThat(DaftarConfig.parse("[reader]\nfolded = false\n").folded).isFalse()
    }

    @Test
    fun `it is read beside daftar-cli's own keys and sections`() {
        val text =
            """
            memory = true

            [audit]
            active = ["applied", "screening"]
            quiet_days = 30

            [reader]
            # Notes open as their own table of contents.
            folded = true

            [check]
            strict = false
            """.trimIndent()
        assertThat(DaftarConfig.parse(text).folded).isTrue()
    }

    @Test
    fun `the key of another section is not this one`() {
        assertThat(DaftarConfig.parse("[audit]\nfolded = true\n").folded).isFalse()
        assertThat(DaftarConfig.parse("folded = true\n").folded).isFalse()
        assertThat(DaftarConfig.parse("[reader]\nwide = true\n\n[check]\nfolded = true\n").folded).isFalse()
        assertThat(DaftarConfig.parse("[reader.print]\nfolded = true\n").folded).isFalse()
        assertThat(DaftarConfig.parse("[[reader]]\nunfolded = true\n").folded).isFalse()
    }

    @Test
    fun `anything but true is the default`() {
        assertThat(DaftarConfig.parse("[reader]\nfolded = \"true\"\n").folded).isFalse()
        assertThat(DaftarConfig.parse("[reader]\nfolded = 1\n").folded).isFalse()
        assertThat(DaftarConfig.parse("[reader]\nfolded =\n").folded).isFalse()
        assertThat(DaftarConfig.parse("[reader]\nfolded\n").folded).isFalse()
        assertThat(DaftarConfig.parse("[reader]\n# folded = true\n").folded).isFalse()
    }

    @Test
    fun `the ways TOML lets the same thing be written are read the same`() {
        assertThat(DaftarConfig.parse("  [ reader ]  \n  folded=true   # as a table of contents\r\n").folded).isTrue()
        assertThat(DaftarConfig.parse("reader.folded = true\n").folded).isTrue()
        assertThat(DaftarConfig.parse("[\"reader\"]\n\"folded\" = true\n").folded).isTrue()
    }

    /**
     * The settings half of `aReadOnlyCloneCanRefuseAgents` in `read-only-vault.md`;
     * `daftar-cli mcp`, the other half, does not run on Android.
     */
    @Test
    fun aReadOnlyCloneCanRefuseAgents() {
        val refusing = DaftarConfig.parse("[agents]\nread_only_clones = false\n")
        assertThat(refusing.agentsInReadOnlyClones).isFalse()
        assertThat(refusing.agentsRefused(readOnly = true)).isTrue()
        // A clone that is not read-only is never touched by the key.
        assertThat(refusing.agentsRefused(readOnly = false)).isFalse()

        listOf(
            "",
            "[agents]\nread_only_clones = true\n",
            "[reader]\nread_only_clones = false\n",
            "read_only_clones = false\n",
        ).forEach { text ->
            assertThat(DaftarConfig.parse(text).agentsInReadOnlyClones).isTrue()
            assertThat(DaftarConfig.parse(text).agentsRefused(readOnly = true)).isFalse()
        }
    }

    @Test
    fun `a file that is not TOML at all is the default and does not throw`() {
        assertThat(DaftarConfig.parse("[[[\n===\n]\n\u0000 = = =\n[reader\nfolded true").folded).isFalse()
    }
}
