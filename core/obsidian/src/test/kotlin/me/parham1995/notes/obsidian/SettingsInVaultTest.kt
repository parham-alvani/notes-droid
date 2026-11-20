package me.parham1995.notes.obsidian

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A setting changed in the app goes to the vault: the rules of
 * `vault-config.md` in notes-linux's `docs/specs/`, section 5, that are pure
 * functions of the vault's `.daftar.toml`, by the test names the spec gives.
 * The same tests through the app's settings and its vault write are in
 * core/data.
 */
class SettingsInVaultTest {
    private val vault = "# mine\n[appearance]\ntheme = \"dark\" # night\n"

    /** A change in a vault that can be written, as the app makes it: where it goes, and the file after it. */
    private fun changed(
        text: String?,
        key: String,
        value: TomlValue,
        platform: String = SettingsFromVault.ANDROID,
    ): String? =
        when (val home = SettingsFromVault.home(text, key, value, writable = true, platform = platform)) {
            SettingsFromVault.Home.Device -> null
            is SettingsFromVault.Home.Vault -> DaftarToml.set(text, home.key, value)
        }

    @Test
    fun aSettingChangedInTheAppGoesToTheVault() {
        // A key that is not there is added at the end of its section.
        assertThat(changed(vault, "appearance.text_size", TomlValue.Whole(16)))
            .isEqualTo("# mine\n[appearance]\ntheme = \"dark\" # night\ntext_size = 16\n")
        // A key that is there has only its line changed, and keeps its comment.
        assertThat(changed(vault, "appearance.theme", TomlValue.Text("light")))
            .isEqualTo("# mine\n[appearance]\ntheme = \"light\" # night\n")
        // A section that is not there is added at the end, with one blank line before it.
        assertThat(changed(vault, "notifications.digest", TomlValue.Flag(false)))
            .isEqualTo("$vault\n[notifications]\ndigest = false\n")
        // A value equal to the default is still written, and a vault with no file gets one.
        assertThat(changed(null, "appearance.theme", TomlValue.Text("system")))
            .isEqualTo("[appearance]\ntheme = \"system\"\n")
    }

    @Test
    fun `only the key's line changes, whatever is around it`() {
        val text =
            "memory = true\r\n\r\n[files]\r\n# hidden from the lists\r\n" +
                "hidden = [\"CLAUDE.md\",\r\n  \"AGENTS.md\"]\r\n" +
                "show_hidden=false\r\n\r\n[check]\r\nstrict = true\r\n"

        val hidden = DaftarToml.set(text, "files.hidden", TomlValue.Texts(listOf("NOTES.md")))
        assertThat(hidden).isEqualTo(
            "memory = true\r\n\r\n[files]\r\n# hidden from the lists\r\nhidden = [\"NOTES.md\"]\r\n" +
                "show_hidden=false\r\n\r\n[check]\r\nstrict = true\r\n",
        )
        // The key is kept as it was written; a value needs one space before it.
        assertThat(DaftarToml.set(text, "files.show_hidden", TomlValue.Flag(true))).contains("show_hidden= true\r\n")
        // A new key goes after the last line of its section, before the blank line that ends it.
        assertThat(DaftarToml.set(text, "files.extra", TomlValue.Whole(1)))
            .contains("show_hidden=false\r\nextra = 1\r\n\r\n[check]")
        // Read back, each says what was written.
        assertThat(DaftarToml.parse(hidden)["files.hidden"]).isEqualTo(TomlValue.Texts(listOf("NOTES.md")))
        val quoted = DaftarToml.set(null, "export.footer", TomlValue.Text("a \"b\" \\ c"))
        assertThat(DaftarToml.parse(quoted)["export.footer"]).isEqualTo(TomlValue.Text("a \"b\" \\ c"))
    }

    @Test
    fun aPlatformReadsItsOwnTable() {
        val size = "appearance.text_size"
        val text =
            "[appearance]\ntext_size = 14\n[macos.appearance]\ntext_size = 15\n" +
                "[android.appearance]\ntext_size = 16\n"

        assertThat(SettingsFromVault.resolved(text, "", platform = "macos").whole(size)).isEqualTo(15)
        assertThat(SettingsFromVault.resolved(text, "", platform = "android").whole(size)).isEqualTo(16)
        assertThat(SettingsFromVault.resolved(text, "", platform = "linux").whole(size)).isEqualTo(14)
        // The platform's table wins wherever it is in the file, and a declined key is declined in it too.
        val first = "[android.appearance]\ntext_size = 16\n[appearance]\ntext_size = 14\n"
        assertThat(SettingsFromVault.resolved(first, "").whole(size)).isEqualTo(16)
        assertThat(SettingsFromVault.resolved(first, "vault_settings = [\"$size\"]\n").whole(size)).isNull()

        // The list holds in a platform's table as in a shared section.
        val shell = SettingsFromVault.resolved("[macos.terminal]\nshell = \"/tmp/x\"\n", "", platform = "macos")
        assertThat(shell.refused).containsExactly("macos.terminal.shell")
        assertThat(shell.values).isEmpty()
        // Another platform's table is not used, and not named as refused either.
        val other =
            SettingsFromVault.resolved(
                "[macos.terminal]\nshell = \"/tmp/x\"\n[linux.appearance]\ntext_size = 20\n",
                "",
            )
        assertThat(other.refused).isEmpty()
        assertThat(other.values).isEmpty()
        // A refused key of the platform's own table is named with its table.
        assertThat(SettingsFromVault.resolved("[android.terminal]\nshell = \"/tmp/x\"\n", "").refused)
            .containsExactly("android.terminal.shell")
        assertThat(SettingsFromVault.resolved("[android.appearance]\ntext_size = 99\n", "").values).isEmpty()

        // A setting only this platform has is read from its table, and from nowhere else.
        val own = "[android.appearance]\ntext_scale = 115\n[appearance]\ntext_scale = 130\n"
        val read = SettingsFromVault.resolved(own, "")
        assertThat(read.whole("android.appearance.text_scale")).isEqualTo(115)
        assertThat(read.refused).containsExactly("appearance.text_scale")
        assertThat(SettingsFromVault.resolved(own, "", platform = "macos").values).isEmpty()

        // The phone's background refresh is its own key: the shared one is the desktops' timer,
        // and neither is read as the other.
        val both = SettingsFromVault.resolved("[git]\nsync_interval = 0\n[android.git]\nsync_interval = 12\n", "")
        assertThat(both.whole("git.sync_interval")).isEqualTo(0)
        assertThat(both.whole("android.git.sync_interval")).isEqualTo(12)
        assertThat(SettingsFromVault.resolved("[android.git]\nsync_interval = 720\n", "").values).isEmpty()
    }

    @Test
    fun aFontIsWrittenToThePlatformsTable() {
        // A key that differs by platform by nature goes to the platform's table, and no shared one is written.
        assertThat(changed(vault, "appearance.font", TomlValue.Text("SF Pro"), platform = "macos"))
            .isEqualTo("$vault\n[macos.appearance]\nfont = \"SF Pro\"\n")
        // A key the platform's table already has is changed there, not in the shared section.
        val both = "[appearance]\ntext_size = 14\n\n[android.appearance]\ntext_size = 15 # the phone\n"
        assertThat(changed(both, "appearance.text_size", TomlValue.Whole(16)))
            .isEqualTo("[appearance]\ntext_size = 14\n\n[android.appearance]\ntext_size = 16 # the phone\n")
        // A setting only this platform has is written to its table.
        assertThat(changed(vault, "android.appearance.text_scale", TomlValue.Whole(115)))
            .isEqualTo("$vault\n[android.appearance]\ntext_scale = 115\n")
    }

    @Test
    fun whatAVaultMayNotHoldGoesToTheDevice() {
        fun home(
            key: String,
            value: TomlValue,
            writable: Boolean = true,
            declined: Boolean = false,
        ) = SettingsFromVault.home(vault, key, value, writable, declined)
        val device = SettingsFromVault.Home.Device

        // A program, web pictures turned on, and a file outside the vault.
        assertThat(home("terminal.shell", TomlValue.Text("/bin/zsh"))).isEqualTo(device)
        assertThat(home(SettingsFromVault.REMOTE_IMAGES, TomlValue.Flag(true))).isEqualTo(device)
        assertThat(home("home.image", TomlValue.Text("/sdcard/Pictures/x.png"))).isEqualTo(device)
        assertThat(home("home.image", TomlValue.Text("../x.png"))).isEqualTo(device)
        // What a vault may hold goes to it.
        assertThat(home(SettingsFromVault.REMOTE_IMAGES, TomlValue.Flag(false)))
            .isEqualTo(SettingsFromVault.Home.Vault(SettingsFromVault.REMOTE_IMAGES))
        assertThat(
            home("home.image", TomlValue.Text("uploads/x.png")),
        ).isEqualTo(SettingsFromVault.Home.Vault("home.image"))
        // Every change in a read-only clone, and with the key or all of them declined, is the device's.
        assertThat(home("appearance.theme", TomlValue.Text("light"), writable = false)).isEqualTo(device)
        assertThat(home("appearance.theme", TomlValue.Text("light"), declined = true)).isEqualTo(device)
        // A key the app does not know, another platform's own, and a value out of range.
        assertThat(home("appearance.plugin", TomlValue.Text("x"))).isEqualTo(device)
        assertThat(home("macos.appearance.dock_badge", TomlValue.Flag(true))).isEqualTo(device)
        assertThat(home("android.appearance.text_scale", TomlValue.Whole(500))).isEqualTo(device)
    }
}
