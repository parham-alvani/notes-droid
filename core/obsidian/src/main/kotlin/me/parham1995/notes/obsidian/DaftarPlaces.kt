package me.parham1995.notes.obsidian

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.parham1995.notes.markdown.StatusType
import me.parham1995.notes.markdown.TaskStatus
import me.parham1995.notes.markdown.TaskStatuses
import java.io.File

/**
 * Daftar's own homes of the settings it read from `.obsidian/`
 * (`no-obsidian-folder.md` in notes-linux's `docs/specs/`, which Daftar for
 * Linux and the Mac follow too): a section of the vault's `.daftar.toml`, or
 * a file in `.daftar/` for data too long for a settings file.
 *
 * Each setting is read from the new place first; when the new place is not
 * there at all (no key of it set, no file), from `.obsidian/` as before. A
 * setting is never put together from both: the new place wins whole.
 *
 * [keys] are the `.daftar.toml`'s keys as the allow-list lets them through
 * ([of]); each function takes the old file's text, null when there is none.
 */
class DaftarPlaces(
    private val keys: Map<String, TomlValue> = emptyMap(),
) {
    private fun text(key: String): String? = (keys[key] as? TomlValue.Text)?.value

    private fun flag(key: String): Boolean? = (keys[key] as? TomlValue.Flag)?.value

    private fun any(prefix: String): Boolean = keys.keys.any { it.startsWith(prefix) }

    /** Where attachments go: `files.attachments`, else `attachmentFolderPath`, else the vault's root (""). */
    fun attachments(appJson: String?): String =
        text(ATTACHMENTS)?.trim('/')
            ?: appJson?.let { runCatching { appSetting(it, "attachmentFolderPath") }.getOrNull() }?.trim()?.trim('/')
            ?: ""

    /** The excluded files: `files.ignore`, else `userIgnoreFilters`. */
    fun ignoreFilters(appJson: String?): List<String> =
        (keys[IGNORE] as? TomlValue.Texts)?.value?.filter { it.isNotBlank() }
            ?: appJson?.let { runCatching { HiddenFiles.ignoreFilters(it) }.getOrNull() }.orEmpty()

    /** Where a day's note is: `[daily]`, else the Daily notes plugin's file. */
    fun dailyNotes(json: String?): DailyNotes {
        if (!any("daily.")) return json?.let { DailyNotes.parse(it) } ?: DailyNotes()
        return DailyNotes(
            folder = text("daily.folder")?.trim()?.trim('/').orEmpty(),
            format = text("daily.format")?.trim()?.takeIf { it.isNotEmpty() } ?: DailyNotes.DEFAULT_FORMAT,
        )
    }

    /** The templates' folder and date and time formats: `[templates]`, else the Templates plugin's file. */
    fun templates(json: String?): Templates {
        if (!any("templates.")) return json?.let { Templates.parse(it) } ?: Templates()
        return Templates(
            folder = text("templates.folder")?.trim()?.trim('/').orEmpty(),
            dateFormat = text("templates.date_format")?.trim()?.ifEmpty { null } ?: Templates.DEFAULT_DATE,
            timeFormat = text("templates.time_format")?.trim()?.ifEmpty { null } ?: Templates.DEFAULT_TIME,
        )
    }

    /** Each property's type: `[properties.types]`, else `types.json`. */
    fun propertyTypes(json: String?): Map<String, String> {
        val types =
            keys.mapNotNull { (key, value) ->
                val name = key.removePrefix(TYPES).takeIf { key.startsWith(TYPES) && it.isNotEmpty() }
                name?.let { (value as? TomlValue.Text)?.value?.let { type -> it to type } }
            }
        if (types.isNotEmpty()) return types.toMap()
        return json?.let { PropertyTypes.parse(it) }.orEmpty()
    }

    /**
     * The task statuses: the `[tasks.statuses.*]` sections, else the Tasks
     * plugin's file. A section with one of the four symbols every vault has
     * changes its name or next symbol.
     */
    fun statuses(json: String?): TaskStatuses {
        if (!any(STATUSES)) return json?.let { TasksPlugin.statuses(it) } ?: TaskStatuses.DEFAULT
        val sections = LinkedHashMap<String, MutableMap<String, String>>()
        keys.forEach { (key, value) ->
            if (!key.startsWith(STATUSES)) return@forEach
            val id = key.removePrefix(STATUSES).substringBeforeLast('.', "")
            val said = (value as? TomlValue.Text)?.value ?: return@forEach
            sections.getOrPut(id) { HashMap() }[key.substringAfterLast('.')] = said
        }
        val defaults = TaskStatuses.DEFAULT.all.associateBy { it.symbol }
        val core = mutableListOf<TaskStatus>()
        val custom = mutableListOf<TaskStatus>()
        sections.values.forEach { section ->
            val symbol = section["symbol"]?.singleOrNull() ?: return@forEach
            val known = defaults[symbol]
            val type = section["type"]?.let(StatusType::parse) ?: known?.type ?: return@forEach
            val status =
                TaskStatus(
                    symbol = symbol,
                    name = section["name"]?.trim()?.ifEmpty { null } ?: known?.name ?: "[$symbol]",
                    next = section["next"]?.singleOrNull() ?: known?.next ?: ' ',
                    type = type,
                )
            if (known != null) core += status else custom += status
        }
        return TaskStatuses.of(core, custom)
    }

    /**
     * What the home page shows: the `home.*` keys, else the Beautitab
     * plugin's file; the quotes from `.daftar/quotes.json` ([quotesJson]),
     * else the plugin's own.
     */
    fun home(
        beautitabJson: String?,
        quotesJson: String?,
    ): Beautitab {
        val plugin = beautitabJson?.let { Beautitab.parse(it) }
        val quotes = quotesJson?.let { runCatching { quotes(it) }.getOrNull() } ?: plugin?.quotes.orEmpty()
        val homeKeys = HOME_KEYS.any { it in keys }
        if (!homeKeys) {
            val base = plugin ?: Beautitab()
            // The plugin shows a quote only from its own list, so a list of Daftar's stands in for it whole.
            val showQuote = if (quotesJson != null) plugin?.quoteAsked == true else base.showQuote
            return base.copy(quotes = quotes, showQuote = showQuote && quotes.isNotEmpty())
        }
        return Beautitab(
            showTime = flag("home.show_time") ?: true,
            twentyFourHour =
                when (text("home.clock")) {
                    "24-hour" -> true
                    "12-hour" -> false
                    else -> null
                },
            showGreeting = flag("home.show_greeting") ?: true,
            greetingText = text("home.greeting")?.trim().orEmpty(),
            showSearch = flag("home.show_search") ?: true,
            showRecentFiles = flag("home.show_recent") ?: true,
            showBookmarks = flag("home.show_bookmarks") ?: false,
            showQuote = (flag("home.show_quote") ?: false) && quotes.isNotEmpty(),
            quotes = quotes,
        )
    }

    companion object {
        /** Daftar's folder at the vault's root, for data too long for `.daftar.toml`. */
        const val FOLDER = ".daftar"
        const val ICONS = "$FOLDER/icons.json"
        const val QUOTES = "$FOLDER/quotes.json"
        const val BOOKMARKS = "$FOLDER/bookmarks.json"

        private const val ATTACHMENTS = "files.attachments"
        private const val IGNORE = "files.ignore"
        private const val TYPES = "properties.types."
        private const val STATUSES = "tasks.statuses."
        private val HOME_KEYS =
            listOf(
                "home.greeting",
                "home.clock",
                "home.show_time",
                "home.show_greeting",
                "home.show_search",
                "home.show_recent",
                "home.show_bookmarks",
                "home.show_quote",
            )

        private val json = Json { ignoreUnknownKeys = true }

        /** The places of a vault whose `.daftar.toml` says [text] (null when it has none), through the allow-list. */
        fun of(
            text: String?,
            root: File? = null,
        ): DaftarPlaces =
            if (text.isNullOrBlank()) {
                DaftarPlaces()
            } else {
                DaftarPlaces(SettingsFromVault.resolved(text, emptyMap(), root = root).values)
            }

        /** The new file's text when there is one, else the old one's: the new place wins whole. */
        fun firstOf(
            daftar: String?,
            obsidian: String?,
        ): String? = daftar ?: obsidian

        /** `.daftar/quotes.json`: `[{"text": "…", "author": "…"}]`. */
        fun quotes(text: String): List<Beautitab.Quote> =
            (json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull { item ->
                val quote = item as? JsonObject ?: return@mapNotNull null
                val said =
                    (quote["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                Beautitab.Quote(said, (quote["author"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
            }

        private fun appSetting(
            appJson: String,
            key: String,
        ): String? = ((json.parseToJsonElement(appJson) as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull
    }
}
