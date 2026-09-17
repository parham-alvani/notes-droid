package me.parham1995.notes.markdown

/**
 * Strips the Obsidian Tasks plugin's trailing emoji metadata off a task line.
 *
 * These pairs are dense in a task-heavy vault -- thousands of them -- and left
 * in the text they render as a wall of emoji and dates at the end of every
 * line. Pulled out, they become chips and the task line reads as the sentence
 * it was written as.
 */
object TaskMetadata {
    /** The plugin's own symbols, with what each one means. */
    private val SYMBOLS =
        mapOf(
            "✅" to "done",
            "➕" to "created",
            "⏳" to "scheduled",
            "❌" to "cancelled",
            "📅" to "due",
            "🔁" to "recurring",
            "⛔" to "blocked",
            "🛫" to "start",
            "🆔" to "id",
            "🔺" to "priority",
            "⏫" to "priority",
            "🔼" to "priority",
            "🔽" to "priority",
            "⏬" to "priority",
        )

    data class Result(
        val text: String,
        val meta: List<TaskMeta>,
    )

    fun split(line: String): Result {
        var remaining = line
        val found = mutableListOf<TaskMeta>()

        // Work from the right: the metadata is always a suffix, and taking the
        // last symbol each time keeps pairs in source order once reversed.
        while (true) {
            val last =
                SYMBOLS.keys
                    .mapNotNull { symbol ->
                        val index = remaining.lastIndexOf(symbol)
                        if (index >= 0) symbol to index else null
                    }.maxByOrNull { it.second } ?: break

            val (symbol, index) = last
            val value = remaining.substring(index + symbol.length).trim()
            // A symbol with prose after it is part of the sentence, not metadata.
            if (value.isNotEmpty() && !looksLikeValue(value)) break

            found += TaskMeta(symbol, value)
            remaining = remaining.substring(0, index).trimEnd()
        }

        return Result(remaining, found.reversed())
    }

    private val VALUE = Regex("""^[0-9A-Za-z/,:. -]{0,32}$""")

    private fun looksLikeValue(value: String): Boolean = VALUE.matches(value)
}
