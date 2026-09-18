package me.parham1995.notes.icons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.text.Normalizer

/**
 * The icon assignments from Obsidian's [Iconic](https://github.com/gfxholo/iconic)
 * plugin, which is where this vault records what to show beside a note or a
 * folder.
 *
 * Resolution follows the plugin: an icon set on the item itself wins, and
 * otherwise the first enabled rule that selects it applies. Files and folders
 * have separate rule lists but share one table of explicit assignments, keyed
 * by vault-relative path.
 *
 * Nothing here decides how an icon *looks*. That is deliberate -- this stays a
 * pure translation of the plugin's file, so the whole of it is testable without
 * a device.
 */
class IconicConfig internal constructor(
    private val items: Map<String, IconSpec>,
    private val fileRules: List<IconRule>,
    private val folderRules: List<IconRule>,
) {
    val isEmpty: Boolean get() = items.isEmpty() && fileRules.isEmpty() && folderRules.isEmpty()

    /** The icon for [path], or null when nothing assigns one. */
    fun forPath(
        path: String,
        isFolder: Boolean,
    ): IconSpec? {
        val key = normalize(path)
        items[key]?.let { return it }
        val target = IconTarget(key, isFolder)
        val rules = if (isFolder) folderRules else fileRules
        return rules.firstOrNull { it.matches(target) }?.icon
    }

    fun forFile(path: String): IconSpec? = forPath(path, isFolder = false)

    fun forFolder(path: String): IconSpec? = forPath(path, isFolder = true)

    companion object {
        val EMPTY = IconicConfig(emptyMap(), emptyList(), emptyList())

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads the plugin's `data.json`.
         *
         * It is walked as a tree rather than deserialised into declared
         * classes. The file is written by a plugin that is free to change its
         * schema between releases and already stores the same field in more
         * than one shape -- a colour is either a name or an object -- so a
         * strict mapping would turn a plugin update into an app that shows no
         * icons at all. Anything unrecognised is skipped instead.
         *
         * @throws kotlinx.serialization.SerializationException if the file is not JSON.
         */
        fun parse(text: String): IconicConfig {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return EMPTY
            return IconicConfig(
                items = parseItems(root["fileIcons"] as? JsonObject),
                fileRules = parseRules(root["fileRules"] as? JsonArray),
                folderRules = parseRules(root["folderRules"] as? JsonArray),
            )
        }

        private fun parseItems(node: JsonObject?): Map<String, IconSpec> {
            if (node == null) return emptyMap()
            val out = LinkedHashMap<String, IconSpec>(node.size)
            for ((path, value) in node) {
                val entry = value as? JsonObject ?: continue
                // `unsynced` also lives on these entries. It is the plugin's own
                // bookkeeping about which installations have seen the change,
                // not a display instruction, so it is ignored.
                val spec = parseSpec(entry) ?: continue
                out[normalize(path)] = spec
            }
            return out
        }

        private fun parseRules(node: JsonArray?): List<IconRule> {
            if (node == null) return emptyList()
            return node.mapNotNull { element ->
                val rule = element as? JsonObject ?: return@mapNotNull null
                val icon = parseSpec(rule) ?: return@mapNotNull null
                IconRule(
                    id = rule.string("id").orEmpty(),
                    name = rule.string("name").orEmpty(),
                    match = RuleMatch.parse(rule.string("match")),
                    conditions =
                        (rule["conditions"] as? JsonArray).orEmpty().mapNotNull { raw ->
                            val condition = raw as? JsonObject ?: return@mapNotNull null
                            IconCondition(
                                source = condition.string("source") ?: return@mapNotNull null,
                                operator = condition.string("operator") ?: return@mapNotNull null,
                                value = condition.string("value").orEmpty(),
                            )
                        },
                    icon = icon,
                    // Absent means enabled; the plugin only writes the flag once
                    // a rule has been switched off and on again.
                    enabled = (rule["enabled"] as? JsonPrimitive)?.contentOrNull != "false",
                )
            }
        }

        private fun parseSpec(entry: JsonObject): IconSpec? {
            val icon = entry.string("icon")?.takeIf { it.isNotBlank() } ?: return null
            val color = parseColor(entry["color"])
            return when {
                icon.startsWith(LUCIDE_PREFIX) -> IconSpec.Glyph(icon.removePrefix(LUCIDE_PREFIX), color)
                // Iconic stores emoji as the characters themselves and every
                // other icon as an id, and ids are ASCII. Flags, skin-tone
                // sequences and ZWJ emoji all fall out on the right side of
                // this without having to enumerate them.
                icon.none { it in 'a'..'z' || it in 'A'..'Z' } -> IconSpec.Emoji(icon, color)
                else -> IconSpec.Glyph(icon, color)
            }
        }

        private fun parseColor(element: kotlinx.serialization.json.JsonElement?): IconColor? =
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let(IconColor::Named)
                is JsonObject -> (element["rgb"] as? JsonPrimitive)?.intOrNull?.let(IconColor::Rgb)
                else -> null
            }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

        /**
         * Paths are compared against the device's filesystem, which hands back
         * whatever Unicode composition it stores. Without this, a folder like
         * `Diet/Çöp şiş` matches on a laptop and silently loses its icon on the
         * phone.
         */
        private fun normalize(path: String): String = Normalizer.normalize(path.trim('/'), Normalizer.Form.NFC)

        private const val LUCIDE_PREFIX = "lucide-"
    }
}
