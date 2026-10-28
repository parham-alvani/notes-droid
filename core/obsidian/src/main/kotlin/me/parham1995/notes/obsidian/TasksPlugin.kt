package me.parham1995.notes.obsidian

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.parham1995.notes.markdown.StatusType
import me.parham1995.notes.markdown.TaskStatus
import me.parham1995.notes.markdown.TaskStatuses

/**
 * The Tasks plugin's settings, for the one thing in them the app follows: the
 * statuses, so `[r]` reads as "In Review", under way, as it does at the desk.
 */
object TasksPlugin {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The statuses in `.obsidian/plugins/obsidian-tasks-plugin/data.json`:
     * `statusSettings.coreStatuses`, then `customStatuses`. An entry whose
     * symbol is not one character, or whose type is not one of the plugin's,
     * is skipped; [TaskStatuses.of] fills in any of the four defaults left
     * out.
     *
     * @throws kotlinx.serialization.SerializationException if the file is not JSON.
     */
    fun statuses(text: String): TaskStatuses {
        val settings = (json.parseToJsonElement(text) as? JsonObject)?.get("statusSettings") as? JsonObject
        return TaskStatuses.of(list(settings?.get("coreStatuses")), list(settings?.get("customStatuses")))
    }

    private fun list(element: Any?): List<TaskStatus> =
        (element as? JsonArray).orEmpty().mapNotNull { entry ->
            val status = entry as? JsonObject ?: return@mapNotNull null

            fun string(key: String) = (status[key] as? JsonPrimitive)?.contentOrNull
            val symbol = string("symbol")?.singleOrNull() ?: return@mapNotNull null
            val type = string("type")?.let(StatusType::parse) ?: return@mapNotNull null
            TaskStatus(
                symbol = symbol,
                name = string("name")?.trim()?.ifEmpty { null } ?: "[$symbol]",
                next = string("nextStatusSymbol")?.singleOrNull() ?: ' ',
                type = type,
            )
        }
}
