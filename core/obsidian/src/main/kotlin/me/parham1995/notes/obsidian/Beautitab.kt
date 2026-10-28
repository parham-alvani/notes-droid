package me.parham1995.notes.obsidian

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.parham1995.notes.markdown.MdDirection
import me.parham1995.notes.markdown.TextDirection
import java.util.Locale

/**
 * The settings of Obsidian's Beautitab plugin, the new-tab page the home page
 * replaces: `.obsidian/plugins/beautitab/data.json`.
 *
 * Every key is optional, and a value of the wrong type is the default, as the
 * Mac reads it. Quotes from the web are off: only "My quotes"
 * (`customQuotes`) are shown, because the page never goes to the network.
 */
data class Beautitab(
    val showTime: Boolean = true,
    /** True for "24-hour", false for "12-hour", null for the phone's own choice. */
    val twentyFourHour: Boolean? = null,
    val showGreeting: Boolean = true,
    /** Empty for "Good morning", "Good afternoon" or "Good evening". */
    val greetingText: String = "",
    val showSearch: Boolean = true,
    val showRecentFiles: Boolean = true,
    val showBookmarks: Boolean = false,
    /** On only when the source is the person's own quotes and there is one. */
    val showQuote: Boolean = false,
    val quotes: List<Quote> = emptyList(),
    /**
     * `customBackground`, then each of `localBackgrounds`: paths or web
     * addresses, in the plugin's order. Blank ones are dropped.
     */
    val backgrounds: List<String> = emptyList(),
) {
    data class Quote(
        val text: String,
        val author: String = "",
    ) {
        /** True when the quote reads right to left, by its first strong letter. */
        val isRtl: Boolean get() = TextDirection.of(text) == MdDirection.RTL

        /**
         * The text in its own script's quotation marks: «…» for Persian, “…”
         * for the rest. In a line of the text's own direction, the opening
         * mark is at the side where the reading starts.
         */
        val quoted: String get() = if (isRtl) "«$text»" else "“$text”"
    }

    /**
     * The first of [backgrounds] that is a picture in the vault, as a
     * vault-relative path; null when there is none. A web address, a
     * `data:` one or a path outside the vault is passed over: the page never
     * goes to the network, and the phone has only the vault.
     */
    val vaultBackground: String?
        get() = backgrounds.firstNotNullOfOrNull(::vaultPicture)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val PICTURES = setOf("jpg", "jpeg", "png", "gif", "webp")

        /**
         * Reads the plugin's `data.json`. Anything but an object is the
         * defaults.
         *
         * @throws kotlinx.serialization.SerializationException if the file is not JSON.
         */
        fun parse(text: String): Beautitab {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return Beautitab()

            // Only true and false count: a 1 or a "true" is a typo, not a choice.
            fun flag(key: String) = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

            fun string(key: String) = root[key].string()?.trim()

            val quotes =
                (root["customQuotes"] as? JsonArray).orEmpty().mapNotNull { item ->
                    val quote = item as? JsonObject ?: return@mapNotNull null
                    val text = quote["text"].string()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    Quote(text, quote["author"].string()?.trim().orEmpty())
                }
            val source = string("quoteSource")
            val local =
                (root["localBackgrounds"] as? JsonArray).orEmpty().mapNotNull { item ->
                    item.string()
                        ?: (item as? JsonObject)?.let {
                            it["path"].string() ?: it["url"].string() ?: it["src"].string()
                        }
                }
            return Beautitab(
                showTime = flag("showTime") ?: true,
                twentyFourHour =
                    when (string("timeFormat")?.lowercase(Locale.ROOT)) {
                        "24-hour" -> true
                        "12-hour" -> false
                        else -> null
                    },
                showGreeting = flag("showGreeting") ?: true,
                greetingText = string("greetingText").orEmpty(),
                showSearch = flag("showInlineSearch") ?: true,
                showRecentFiles = flag("showRecentFiles") ?: true,
                showBookmarks = flag("showBookmarks") ?: false,
                showQuote =
                    (flag("showQuote") ?: false) && (source == null || source == MY_QUOTES) && quotes.isNotEmpty(),
                quotes = quotes,
                backgrounds =
                    (
                        listOfNotNull(
                            string("customBackground"),
                        ) + local
                    ).map { it.trim() }.filter { it.isNotEmpty() },
            )
        }

        /** Beautitab's name for the person's own quotes. */
        const val MY_QUOTES = "My quotes"

        /**
         * [setting] as a picture's path in the vault, or null: a web address,
         * a `data:` one, an absolute path, one that climbs out with `..`, or
         * a file that is no picture the page can draw.
         */
        fun vaultPicture(setting: String): String? {
            val text = setting.trim()
            if (text.isEmpty() || "://" in text || text.startsWith("data:") || text.startsWith("~")) return null
            val path = text.trimStart('/')
            if (text.startsWith("/") || path.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
            val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return path.takeIf { extension in PICTURES }
        }

        private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/** Part of the day, for the greeting. */
enum class PartOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    ;

    companion object {
        /** Morning from 5 to 12, afternoon to 18, else evening, as the Mac has it. */
        fun of(hour: Int): PartOfDay =
            when (hour) {
                in 5 until 12 -> MORNING
                in 12 until 18 -> AFTERNOON
                else -> EVENING
            }
    }
}

/** The home page's rules, apart from its view. */
object Home {
    /**
     * The person's greeting with `{{greeting}}` as [part] (the part of the
     * day, in the phone's language); [fallback] -- "Good morning" and its
     * kind -- when they wrote none.
     */
    fun greeting(
        text: String,
        part: String,
        fallback: String,
    ): String = if (text.isBlank()) fallback else text.replace("{{greeting}}", part)
}
