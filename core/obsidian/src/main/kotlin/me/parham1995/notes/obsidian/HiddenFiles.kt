package me.parham1995.notes.obsidian

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which files the lists leave out: the browser, search, tags, the graph.
 *
 * Two kinds of file are not for a person to browse. What the vault itself
 * says to leave out -- Obsidian's "Excluded files", `userIgnoreFilters` in
 * `.obsidian/app.json`, where an `uploads/` of attachments usually sits --
 * and the files of instructions that AI agents read, a `CLAUDE.md` or an
 * `AGENTS.md`, of which a vault written with their help has one in nearly
 * every folder.
 *
 * Hidden is only about lists. A hidden file is still synced and still on
 * disk, a link or an embed into it still resolves, and it opens as any note
 * does. That is also how Obsidian treats an excluded file.
 */
data class HiddenFiles(
    /** The entries of Obsidian's `userIgnoreFilters`, as written. */
    val ignoreFilters: List<String> = emptyList(),
    /** File names hidden in any folder, matched whole and with case. */
    val names: Set<String> = emptySet(),
) {
    /**
     * The filters as they are matched. A `/regex/` that does not compile is
     * left out rather than failing every list: Obsidian is JavaScript, and a
     * pattern it takes can still be one that Java or Android's ICU refuses.
     */
    private val filters: List<Filter> = ignoreFilters.mapNotNull(Filter::of)

    /** Whether nothing is hidden at all, so a caller can skip the work. */
    val isEmpty: Boolean get() = filters.isEmpty() && names.isEmpty()

    /** Whether [path], relative to its vault, is kept out of the lists. */
    fun hides(path: String): Boolean {
        if (path.isEmpty()) return false
        if (path.substringAfterLast('/') in names) return true
        return filters.any { it.matches(path) }
    }

    private sealed interface Filter {
        fun matches(path: String): Boolean

        /**
         * A folder or a file, by its path from the vault's root.
         *
         * `uploads/` and `uploads` both mean the folder and everything in
         * it, and `uploads` also a file of that name -- but not `uploads2/`,
         * which only shares the first letters. Obsidian compares the start
         * of the path, so the boundary is a little stricter here; nobody
         * means "every path that starts with `up`".
         */
        data class Path(
            val prefix: String,
        ) : Filter {
            override fun matches(path: String): Boolean = path == prefix || path.startsWith("$prefix/")
        }

        /** `/pattern/`, as Obsidian writes a regular expression: found anywhere in the path. */
        data class Pattern(
            val regex: Regex,
        ) : Filter {
            override fun matches(path: String): Boolean = regex.containsMatchIn(path)
        }

        companion object {
            fun of(raw: String): Filter? {
                val entry = raw.trim()
                if (entry.length > 2 && entry.startsWith("/") && entry.endsWith("/")) {
                    return runCatching { Pattern(Regex(entry.substring(1, entry.length - 1))) }.getOrNull()
                }
                // A leading `./` or `/` names the root the same way no slash
                // does, and the trailing slash only says it is a folder.
                val prefix = entry.removePrefix("./").trim('/')
                return if (prefix.isEmpty()) null else Path(prefix)
            }
        }
    }

    companion object {
        /** Nothing hidden: what "show hidden files" asks for. */
        val NONE = HiddenFiles()

        /**
         * The instructions AI agents read, by the names their tools look
         * for: Claude Code's `CLAUDE.md` and the personal `CLAUDE.local.md`
         * beside it, the `AGENTS.md` that Codex and others share, Gemini
         * CLI's `GEMINI.md`. Whole names only -- a `MyCLAUDE.md` is a note
         * someone wrote, and a `CLAUDE.md.bak` is not one of these.
         */
        val AGENT_FILES = setOf("CLAUDE.md", "CLAUDE.local.md", "AGENTS.md", "GEMINI.md")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The excluded files in `.obsidian/app.json`. The file holds every
         * editor setting Obsidian has, and says nothing of this one until it
         * has been changed from empty.
         *
         * @throws kotlinx.serialization.SerializationException if the file is not JSON.
         */
        fun ignoreFilters(appJson: String): List<String> {
            val root = json.parseToJsonElement(appJson) as? JsonObject ?: return emptyList()
            val filters = root["userIgnoreFilters"] as? JsonArray ?: return emptyList()
            return filters
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull }
                .filter { it.isNotBlank() }
        }
    }
}
