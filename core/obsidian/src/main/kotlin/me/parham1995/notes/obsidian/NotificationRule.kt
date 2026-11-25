package me.parham1995.notes.obsidian

import me.parham1995.notes.markdown.MarkdownParser
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * A notification the person defines: at [at] each day, when [note] has no
 * heading for the day, say so. `notification-rules.md` in notes-linux's
 * `docs/specs/`, which Daftar for Linux and the Mac follow too; change a rule
 * here and theirs have to follow.
 *
 * A rule is a section of the vault's `.daftar.toml`:
 *
 * ```toml
 * [notifications.rules.diary]
 * note = "Diet/Diary.md"
 * heading = "D MMM YYYY · dddd"
 * at = "21:00"
 * ```
 *
 * Nothing is written: the check only reads the note.
 */
data class NotificationRule(
    /** The section's last name: `diary` of `[notifications.rules.diary]`. */
    val id: String,
    /** The note's path in the vault. */
    val note: String,
    /** A moment.js format of the day's heading. */
    val heading: String,
    /** When the check is, on the device's wall clock. */
    val at: LocalTime,
    /** The alert's title as the rule says it; null when it says none. */
    val title: String? = null,
    /** The alert's body as the rule says it; null when it says none. */
    val message: String? = null,
) {
    /** The heading the note should have on [day]: the format with the time at 00:00. */
    fun expected(day: LocalDate): String = MomentFormat.format(heading, day.atStartOfDay())

    /**
     * Whether [text], the note's text (null when there is no such note), has
     * no heading for [day]: no heading, read as the note parser reads them,
     * whose trimmed text starts with [expected]. A note that is not there has
     * no heading, so the alert also tells of a wrong path.
     */
    fun isMissing(
        text: String?,
        day: LocalDate,
    ): Boolean {
        if (text.isNullOrEmpty()) return true
        val wanted = expected(day)
        return MarkdownParser.parseNote(text).headings.none { it.text.trim().startsWith(wanted) }
    }

    /** The alert's title: [title], else the note's name without `.md`. */
    val alertTitle: String
        get() = title ?: note.substringAfterLast('/').removeSuffix(".md")

    /** The alert's body on [day]: [message], else what heading is missing. */
    fun body(day: LocalDate): String = message ?: "No heading for today yet: ${expected(day)}"

    companion object {
        /** The settings' section every rule is under. */
        const val SECTION = "notifications.rules"

        private val ID = Regex("[A-Za-z0-9_-]+")
        private val TIME = Regex("""([01]\d|2[0-3]):([0-5]\d)""")

        /** Whether [key] is one a rule names (`notifications.rules.<id>.<key>`), whatever its value. */
        internal fun keyOf(key: String): Pair<String, String>? {
            if (!key.startsWith("$SECTION.")) return null
            val inner = key.removePrefix("$SECTION.")
            val id = inner.substringBeforeLast('.', "")
            if (!ID.matches(id)) return null
            return id to inner.substringAfterLast('.')
        }

        /** Every rule [settings] holds, in the order the file writes their sections. */
        fun all(
            settings: ResolvedSettings,
            root: File? = null,
        ): List<NotificationRule> = all(settings.values, root)

        /**
         * Every rule of [values], each setting under its full dotted name. A
         * section whose id has a character the spec does not allow is no rule;
         * a rule with no `note`, `heading` or `at`, or with a key that does not
         * fit, is left out whole and the others stand. [root] is the vault's
         * folder when it is known, for the in-vault check of `note`.
         */
        fun all(
            values: Map<String, TomlValue>,
            root: File? = null,
        ): List<NotificationRule> {
            val sections = LinkedHashMap<String, MutableMap<String, TomlValue>>()
            values.forEach { (key, value) ->
                val (id, name) = keyOf(key) ?: return@forEach
                sections.getOrPut(id) { HashMap() }[name] = value
            }
            return sections.mapNotNull { (id, keys) -> rule(id, keys, root) }
        }

        private fun rule(
            id: String,
            keys: Map<String, TomlValue>,
            root: File?,
        ): NotificationRule? {
            val note = line(keys["note"]) ?: return null
            if (note.isBlank() || !SettingsFromVault.inVault(note, root)) return null
            val heading = line(keys["heading"])?.takeIf { it.isNotBlank() } ?: return null
            val time = TIME.matchEntire(line(keys["at"]) ?: return null) ?: return null
            val (hour, minute) = time.destructured
            // Optional, but a value that is there has to fit, or the rule is left out whole.
            val title = optional(keys["title"]) ?: return null
            val message = optional(keys["message"]) ?: return null
            return NotificationRule(
                id = id,
                note = note,
                heading = heading,
                at = LocalTime.of(hour.toInt(), minute.toInt()),
                title = title.value,
                message = message.value,
            )
        }

        /** Text of one line, or null. */
        private fun line(value: TomlValue?): String? =
            (value as? TomlValue.Text)?.value?.takeIf { text -> text.none { it.isISOControl() } }

        /** An optional key's value, empty as none; null when it is there and does not fit. */
        private fun optional(value: TomlValue?): Optional? {
            if (value == null) return Optional(null)
            val text = line(value) ?: return null
            return Optional(text.takeIf { it.isNotBlank() })
        }

        private data class Optional(
            val value: String?,
        )
    }
}
