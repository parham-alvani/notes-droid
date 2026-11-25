package me.parham1995.notes.obsidian

import java.io.File

/** The base direction a folder gives its notes: each paragraph's own, or one for all. */
enum class FolderDirection { AUTO, RTL, LTR }

/**
 * What one note takes from the `[folders."<folder>"]` sections of its vault's
 * `.daftar.toml` (`more-vault-settings.md` in notes-linux's `docs/specs/`,
 * section 6): null for a key no folder above the note sets.
 */
data class FolderRule(
    val direction: FolderDirection? = null,
    val folded: Boolean? = null,
    val template: String? = null,
)

/**
 * The folder rules of a vault. The most specific folder wins, key by key: a
 * note in `Companies/Snapp/Roles` takes `Companies/Snapp`'s `template` and
 * `Companies`'s `folded` when only those set them.
 */
class FolderRules(
    private val byFolder: Map<String, FolderRule> = emptyMap(),
) {
    /** What the note at [path] takes. */
    fun forNote(path: String): FolderRule {
        // The note's folders, nearest first.
        val folders =
            generateSequence(path.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "").ifEmpty { null } }
                .takeWhile { it.isNotEmpty() }
                .mapNotNull { byFolder[it] }
                .toList()
        return FolderRule(
            direction = folders.firstNotNullOfOrNull { it.direction },
            folded = folders.firstNotNullOfOrNull { it.folded },
            template = folders.firstNotNullOfOrNull { it.template },
        )
    }

    val isEmpty: Boolean get() = byFolder.isEmpty()

    companion object {
        const val SECTION = "folders"

        /** The folder and the key of `folders.<folder>.<key>`, or null for another key. */
        fun keyOf(key: String): Pair<String, String>? {
            if (!key.startsWith("$SECTION.")) return null
            val inner = key.removePrefix("$SECTION.")
            val folder = inner.substringBeforeLast('.', "").trim('/')
            if (folder.isEmpty()) return null
            return folder to inner.substringAfterLast('.')
        }

        /** The rules of a `.daftar.toml`'s text; a value that does not fit is as not there. */
        fun parse(
            text: String?,
            root: File? = null,
        ): FolderRules = of(DaftarToml.parse(text.orEmpty()), root)

        fun of(
            values: Map<String, TomlValue>,
            root: File? = null,
        ): FolderRules {
            val out = LinkedHashMap<String, FolderRule>()
            values.forEach { (key, value) ->
                val (folder, name) = keyOf(key) ?: return@forEach
                if (!SettingsFromVault.inVault(folder, null)) return@forEach
                val rule = out[folder] ?: FolderRule()
                out[folder] =
                    when (name) {
                        "direction" ->
                            (value as? TomlValue.Text)
                                ?.let { said ->
                                    FolderDirection.entries.firstOrNull { it.name.equals(said.value, true) }
                                }?.let { rule.copy(direction = it) } ?: rule
                        "folded" -> (value as? TomlValue.Flag)?.let { rule.copy(folded = it.value) } ?: rule
                        "template" ->
                            (value as? TomlValue.Text)
                                ?.value
                                ?.trim('/')
                                ?.takeIf { it.isNotEmpty() && SettingsFromVault.inVault(it, root) }
                                ?.let { rule.copy(template = it) } ?: rule
                        else -> rule
                    }
            }
            return FolderRules(out)
        }
    }
}
