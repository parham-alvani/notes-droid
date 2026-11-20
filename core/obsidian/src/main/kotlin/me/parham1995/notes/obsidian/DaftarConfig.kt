package me.parham1995.notes.obsidian

/**
 * What a vault says about itself to Daftar on every device, in the
 * `.daftar.toml` at its root, beside `daftar-cli`'s own keys and sections:
 *
 * ```toml
 * [reader]
 * folded = true
 *
 * [agents]
 * read_only_clones = false
 * ```
 *
 * [folded]: a note opens with its sections folded, each heading saying what
 * is under it (`folded-sections.md` in notes-linux's `docs/specs/`, which
 * Daftar for Linux and the Mac follow too). A file that is not there, a key
 * that is not, the key in another section and a value that is not `true` are
 * all the default.
 *
 * [agentsInReadOnlyClones]: whether a read-only clone of the vault is served
 * to an agent (`read-only-vault.md` there, section 5). Only `false` under
 * `[agents]` refuses; no key, `true` and the key in another section do not.
 * Nothing in this app sends a note to a model today; [agentsRefused] is what
 * such a feature has to ask first.
 *
 * These two are what the vault says of itself and are always read. The app's
 * own settings that the same file may carry are [SettingsFromVault]'s.
 *
 * Not Obsidian's, but read the way its settings are: a small file the vault
 * carries, synced as it is and parsed when asked for.
 */
data class DaftarConfig(
    val folded: Boolean = false,
    val agentsInReadOnlyClones: Boolean = true,
) {
    /**
     * Whether an agent is refused this vault in a clone that is [readOnly]:
     * anything that would send a note's text to a model is off there, and
     * says why. A clone that is not read-only is never touched by the key.
     */
    fun agentsRefused(readOnly: Boolean): Boolean = readOnly && !agentsInReadOnlyClones

    companion object {
        private const val FOLDED = "reader.folded"
        private const val AGENTS = "agents.read_only_clones"

        /** Why an agent feature is off, in the words `daftar-cli mcp` uses. */
        const val AGENTS_REFUSED =
            "this vault is not open to agents in a read-only clone (agents.read_only_clones in .daftar.toml)"

        /**
         * Reads the keys this app follows, through [DaftarToml]. Nothing in
         * it can throw, so a file `daftar-cli` grows new sections in costs
         * nothing here.
         */
        fun parse(text: String): DaftarConfig {
            val keys = DaftarToml.parse(text)
            return DaftarConfig(
                folded = keys[FOLDED] == TomlValue.Flag(true),
                agentsInReadOnlyClones = keys[AGENTS] != TomlValue.Flag(false),
            )
        }
    }
}
