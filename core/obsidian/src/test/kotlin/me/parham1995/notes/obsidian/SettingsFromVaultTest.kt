package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The app's settings from the vault's `.daftar.toml`: the tests
 * `vault-config.md` in notes-linux's `docs/specs/` asks of every app, by the
 * names it gives them. The author's order and where a change in the app is
 * written need a repository and a settings store, so those two are in
 * core/data.
 */
class SettingsFromVaultTest {
    private val size = "appearance.text_size"
    private val theme = "appearance.theme"

    @Test
    fun theVaultsValueWins() {
        val computer = "[appearance]\ntext_size = 14\n"

        val both = SettingsFromVault.resolved("[appearance]\ntext_size = 16\n", computer)
        assertThat(both.whole(size)).isEqualTo(16)
        assertThat(both.from(size)).isEqualTo(SettingSource.VAULT)

        val noVaultKey = SettingsFromVault.resolved("[appearance]\ntheme = \"dark\"\n", computer)
        assertThat(noVaultKey.whole(size)).isEqualTo(14)
        assertThat(noVaultKey.from(size)).isEqualTo(SettingSource.COMPUTER)

        val neither = SettingsFromVault.resolved(null, "")
        assertThat(neither[size]).isNull()
        assertThat(neither.from(size)).isEqualTo(SettingSource.DEFAULT)
    }

    @Test
    fun aComputerCanDecline() {
        val vault = "[appearance]\ntext_size = 16\ntheme = \"light\"\n\n[reader]\nfolded = true\n"

        val all = SettingsFromVault.resolved(vault, "vault_settings = false\n\n[appearance]\ntext_size = 14\n")
        assertThat(all.whole(size)).isEqualTo(14)
        assertThat(all[theme]).isNull()
        // Still known to be the vault's, so Settings can offer to follow it again.
        assertThat(all.vaultSets).containsExactly(size, theme)

        val one =
            SettingsFromVault.resolved(
                vault,
                "vault_settings = [\"appearance.text_size\"]\n\n[appearance]\ntext_size = 14\n",
            )
        assertThat(one.whole(size)).isEqualTo(14)
        assertThat(one.from(size)).isEqualTo(SettingSource.COMPUTER)
        assertThat(one.text(theme)).isEqualTo("light")
        assertThat(one.from(theme)).isEqualTo(SettingSource.VAULT)

        // What the vault says of itself is no app setting: read either way, and never refused.
        assertThat(DaftarConfig.parse(vault).folded).isTrue()
        assertThat(all.refused).isEmpty()
        assertThat(one.refused).isEmpty()
    }

    @Test
    fun theVaultStartsNoProgram() {
        val computer =
            """
            vault = "/home/me/notes"

            [terminal]
            shell = "/bin/zsh"

            [editor]
            nvim = "/usr/bin/nvim"
            nvim_config = "user"
            vim_mode = false
            neovide = false

            [wakatime]
            enabled = false
            """.trimIndent()
        val tries =
            mapOf(
                "terminal.shell" to "[terminal]\nshell = \"/tmp/x\"\n",
                "editor.nvim" to "[editor]\nnvim = \"/tmp/x\"\n",
                "editor.nvim_config" to "[editor]\nnvim_config = \"x.lua\"\n",
                "editor.vim_mode" to "[editor]\nvim_mode = true\n",
                "editor.neovide" to "[editor]\nneovide = true\n",
                "wakatime.enabled" to "[wakatime]\nenabled = true\n",
                "vault" to "vault = \"/tmp\"\n",
                "vault_settings" to "vault_settings = true\n",
            )
        val own = SettingsFromVault.resolved(null, computer)

        tries.forEach { (key, vault) ->
            val resolved = SettingsFromVault.resolved(vault, computer)
            assertThat(resolved.refused).containsExactly(key)
            assertThat(resolved[key]).isEqualTo(own[key])
            assertThat(resolved.from(key)).isNotEqualTo(SettingSource.VAULT)
            assertThat(SettingsFromVault.mayBeSet(key)).isFalse()
        }
        // A key of one of the app's own sections that the list does not have is refused too.
        assertThat(SettingsFromVault.resolved("[appearance]\nplugin = \"x\"\n", "").refused)
            .containsExactly("appearance.plugin")
        // Left alone, neither used nor refused: a key the app does not know under an open
        // section, a key of a section that is not the app's, and an unknown top-level key.
        val alone =
            SettingsFromVault.resolved(
                "memory = true\n\n[notifications]\nsound = \"x\"\n\n[capture]\nnew = 1\n\n[okf]\nactor = \"x\"\n\n[check]\nokf = true\n",
                "",
            )
        assertThat(alone.refused).isEmpty()
        assertThat(alone.values).isEmpty()
    }

    @Test
    fun theVaultOnlyTurnsWebPicturesOff() {
        val key = SettingsFromVault.REMOTE_IMAGES
        val on = "[appearance]\nremote_images = true\n"
        val off = "[appearance]\nremote_images = false\n"

        assertThat(SettingsFromVault.resolved(off, on).flag(key)).isFalse()
        assertThat(SettingsFromVault.resolved(on, off).flag(key)).isFalse()
        assertThat(SettingsFromVault.resolved(on, off).from(key)).isEqualTo(SettingSource.COMPUTER)
        assertThat(SettingsFromVault.resolved(on, on).from(key)).isEqualTo(SettingSource.COMPUTER)
    }

    @Test
    fun aPathFromTheVaultStaysInIt() {
        val key = "home.image"
        val computer = "[home]\nimage = \"mine.png\"\n"
        val outside = Files.createTempDirectory("outside").toFile()
        val root = Files.createTempDirectory("vault").toFile()
        try {
            File(root, "uploads").mkdirs()
            File(root, "uploads/home.webp").writeText("picture")
            File(outside, "secret.png").writeText("not the vault's")
            Files.createSymbolicLink(File(root, "uploads/out.png").toPath(), File(outside, "secret.png").toPath())

            val inside = SettingsFromVault.resolved("[home]\nimage = \"uploads/home.webp\"\n", computer, root)
            assertThat(inside.text(key)).isEqualTo("uploads/home.webp")
            assertThat(inside.from(key)).isEqualTo(SettingSource.VAULT)
            // An empty path is accepted, and means none.
            val none = SettingsFromVault.resolved("[home]\nimage = \"\"\n", computer, root)
            assertThat(none.text(key)).isEmpty()
            assertThat(none.from(key)).isEqualTo(SettingSource.VAULT)

            listOf("/etc/passwd", "~/x.png", "../x.png", "uploads/../../x.png", "uploads/out.png").forEach { path ->
                val resolved = SettingsFromVault.resolved("[home]\nimage = \"$path\"\n", computer, root)
                assertThat(resolved.text(key)).isEqualTo("mine.png")
                assertThat(resolved.from(key)).isEqualTo(SettingSource.COMPUTER)
            }
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun aWrongValueIsAsNotThere() {
        val computer = "[appearance]\ntext_size = 14\n"

        // 10 to 28 is the range of the apps' own control.
        listOf("\"big\"", "-3", "9", "29", "14.5", "true", "[14]").forEach { wrong ->
            val resolved = SettingsFromVault.resolved("[appearance]\ntext_size = $wrong\n", computer)
            assertThat(resolved.whole(size)).isEqualTo(14)
            assertThat(resolved.from(size)).isEqualTo(SettingSource.COMPUTER)
            // Wrong, not forbidden: it is not one of the keys a vault may not set.
            assertThat(resolved.refused).isEmpty()
        }
    }

    @Test
    fun `the values are read as TOML writes them`() {
        val keys =
            DaftarToml.parse(
                """
                top = 1

                [files]
                hidden = ["CLAUDE.md", 'AGENTS.md',
                    "a # b.md", # the last one
                ]
                show_hidden = false # not shown

                [export]
                footer = "page # of \"many\""

                [git]
                sync_interval = 1_440
                """.trimIndent(),
            )

        assertThat(keys["top"]).isEqualTo(TomlValue.Whole(1))
        assertThat(keys["files.hidden"]).isEqualTo(TomlValue.Texts(listOf("CLAUDE.md", "AGENTS.md", "a # b.md")))
        assertThat(keys["files.show_hidden"]).isEqualTo(TomlValue.Flag(false))
        assertThat(keys["export.footer"]).isEqualTo(TomlValue.Text("page # of \"many\""))
        assertThat(keys["git.sync_interval"]).isEqualTo(TomlValue.Whole(1440))
        assertThat(DaftarToml.parse("[files]\nhidden = []\n")["files.hidden"]).isEqualTo(TomlValue.Texts(emptyList()))
    }
}
