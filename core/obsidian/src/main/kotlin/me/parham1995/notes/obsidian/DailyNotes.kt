package me.parham1995.notes.obsidian

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.util.Locale

/**
 * Where Obsidian's core Daily notes plugin keeps a day's note.
 *
 * Only the two settings that decide the path are read. The template is what a
 * new note starts from, and this app does not create notes.
 */
data class DailyNotes(
    /** Vault-relative, empty for the root. */
    val folder: String = "",
    /** A moment.js format, which may itself contain `/` and so subfolders. */
    val format: String = DEFAULT_FORMAT,
) {
    /** The vault-relative path of [date]'s note. */
    fun pathFor(
        date: LocalDate,
        locale: Locale = Locale.ENGLISH,
    ): String {
        val name = MomentFormat.format(format, date, locale).trim('/')
        val prefix = folder.trim().trim('/')
        return (if (prefix.isEmpty()) name else "$prefix/$name") + ".md"
    }

    companion object {
        const val DEFAULT_FORMAT = "YYYY-MM-DD"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads `.obsidian/daily-notes.json`.
         *
         * Obsidian writes the file only once a setting has been changed, and
         * leaves a setting out -- or writes it empty -- when it is the default.
         * So absent and blank both mean the default, and a vault with no file at
         * all is read as [DailyNotes] with nothing set.
         *
         * @throws kotlinx.serialization.SerializationException if the file is not JSON.
         */
        fun parse(text: String): DailyNotes {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return DailyNotes()

            fun string(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            return DailyNotes(
                folder = string("folder")?.trim()?.trim('/').orEmpty(),
                format = string("format")?.trim() ?: DEFAULT_FORMAT,
            )
        }
    }
}
