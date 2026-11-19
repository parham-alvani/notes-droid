package me.parham1995.notes.obsidian

/**
 * What a vault says about itself to Daftar on every device, in the
 * `.daftar.toml` at its root, beside `daftar-cli`'s own keys and sections:
 *
 * ```toml
 * [reader]
 * folded = true
 * ```
 *
 * [folded]: a note opens with its sections folded, each heading saying what
 * is under it (`folded-sections.md` in notes-linux's `docs/specs/`, which
 * Daftar for Linux and the Mac follow too). A file that is not there, a key
 * that is not, the key in another section and a value that is not `true` are
 * all the default.
 *
 * Not Obsidian's, but read the way its settings are: a small file the vault
 * carries, synced as it is and parsed when asked for.
 */
data class DaftarConfig(
    val folded: Boolean = false,
) {
    companion object {
        private const val READER = "reader"
        private const val FOLDED = "folded"

        /**
         * Reads the one key this app follows. Not a TOML parser: the file is
         * read line by line for its table headers and for `folded` under
         * `[reader]` (or `reader.folded` before any table), which is all that
         * takes. Nothing in it can throw, so a file `daftar-cli` grows new
         * sections in costs nothing here.
         */
        fun parse(text: String): DaftarConfig {
            var table = ""
            var folded = false
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (line.startsWith("[")) {
                    // `[[array.of.tables]]` is a table of its own name too.
                    table = line.trim('[', ']').split('.').joinToString(".") { unquoted(it) }
                    continue
                }
                val key = line.substringBefore('=', "").split('.').joinToString(".") { unquoted(it) }
                if (key.isEmpty()) continue
                val path = if (table.isEmpty()) key else "$table.$key"
                if (path == "$READER.$FOLDED") folded = line.substringAfter('=').trim() == "true"
            }
            return DaftarConfig(folded = folded)
        }

        private fun unquoted(name: String): String = name.trim().trim('"', '\'').trim()
    }
}
