package me.parham1995.notes.obsidian

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDateTime
import java.util.Locale

/**
 * Obsidian's core Templates plugin: where the templates are, and how
 * `{{date}}` and `{{time}}` are written when they say no format of their own.
 */
data class Templates(
    /** Vault-relative, without slashes at either end; empty when the vault has not set one. */
    val folder: String = "",
    val dateFormat: String = DEFAULT_DATE,
    val timeFormat: String = DEFAULT_TIME,
) {
    /**
     * [template] with its variables filled: `{{title}}`, `{{date}}`,
     * `{{time}}`, and `{{date:FORMAT}}` or `{{time:FORMAT}}` in a moment.js
     * format. Anything else between braces is left as written, as Obsidian
     * leaves it.
     */
    fun fill(
        template: String,
        title: String,
        at: LocalDateTime,
        locale: Locale = Locale.ENGLISH,
    ): String =
        VARIABLE.replace(template) { match ->
            val format = match.groupValues[2].trim().takeIf { match.groupValues[2].isNotEmpty() }
            when (match.groupValues[1].lowercase()) {
                "title" -> title
                "date" -> MomentFormat.format(format ?: dateFormat, at, locale)
                "time" -> MomentFormat.format(format ?: timeFormat, at, locale)
                else -> match.value
            }
        }

    /** Whether [path] is a template, so it can be offered as one. */
    fun holds(path: String): Boolean = folder.isNotEmpty() && path.startsWith("$folder/") && path.endsWith(".md")

    companion object {
        const val DEFAULT_DATE = "YYYY-MM-DD"
        const val DEFAULT_TIME = "HH:mm"

        /** Every brace escaped: Android's regex engine is ICU, which refuses a bare one. */
        private val VARIABLE = Regex("""\{\{\s*(title|date|time)(?::([^\}]*))?\s*\}\}""", RegexOption.IGNORE_CASE)

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads `.obsidian/templates.json`. Like the daily notes settings, a
         * setting left at its default is not written, and blank means default.
         *
         * @throws kotlinx.serialization.SerializationException if the file is not JSON.
         */
        fun parse(text: String): Templates {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return Templates()

            fun string(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            return Templates(
                folder = string("folder")?.trim('/').orEmpty(),
                dateFormat = string("dateFormat") ?: DEFAULT_DATE,
                timeFormat = string("timeFormat") ?: DEFAULT_TIME,
            )
        }

        /**
         * A note's title as a file name: the characters Obsidian refuses in
         * one taken out, and blank when nothing is left.
         */
        fun fileName(title: String): String =
            title
                .replace(FORBIDDEN, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trim('.')
                .trim()

        /**
         * Where the note a link to nothing names is made, as the Mac makes it:
         * `[[Folder/Name]]` in that folder, a bare `[[Name]]` beside the note
         * [from] that holds the link. Null when the link names no note that
         * could be made -- an empty name, or a `..` that climbs out of the vault.
         */
        fun pathForLink(
            target: String,
            from: String,
        ): String? {
            val parts =
                target
                    .trim()
                    .trim('/')
                    .split('/')
                    .map { it.trim() }
            if (parts.any { it == ".." || it == "." }) return null
            val name = fileName(parts.last().removeSuffix(".md"))
            if (name.isEmpty()) return null
            val folder =
                if (parts.size > 1) {
                    parts.dropLast(1).filter { it.isNotEmpty() }.joinToString("/")
                } else {
                    from.substringBeforeLast('/', "")
                }
            return if (folder.isEmpty()) "$name.md" else "$folder/$name.md"
        }

        private val FORBIDDEN = Regex("""[\\/:*?"<>|#^\[\]]""")
    }
}
