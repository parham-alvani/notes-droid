package me.parham1995.notes.obsidian

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `.obsidian/types.json`: the type Obsidian's Properties view gives each
 * property -- `text`, `number`, `date`, `datetime`, `checkbox`, `multitext`,
 * `tags`, `aliases`. A base reads it to know whether `2026` is a number and
 * whether a property with one value is still a list.
 */
object PropertyTypes {
    private val json = Json { ignoreUnknownKeys = true }

    /** Property name to type; empty when the file says nothing usable. */
    fun parse(text: String): Map<String, String> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
        val types = root["types"] as? JsonObject ?: return emptyMap()
        return types.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()
    }
}
