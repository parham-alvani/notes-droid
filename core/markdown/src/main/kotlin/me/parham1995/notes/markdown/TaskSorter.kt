package me.parham1995.notes.markdown

/**
 * The vault's `task-sorter` plugin: "Sort tasks (done first, undone last)".
 *
 * A port of the plugin's own rule rather than an idea of what sorting should
 * be, so the phone and the desk move the same lines. The note is cut into
 * sections at every `#` to `######` heading. In each one a root task is an
 * unindented `- [ ]`, `- [x]`, `- [X]` or `- [-]` line, and its block is that
 * line and every line under it that starts with four spaces or a tab -- its
 * sub-items and notes go with it. Everything else stays exactly where it is,
 * and the task blocks are put back into the places task blocks held, done and
 * cancelled first, the rest after, each group in its old order.
 *
 * Not fence-aware, because the plugin is not: a `# comment` in a code block
 * starts a section there too. The rule matching the desk matters more here
 * than the rule being clever.
 */
object TaskSorter {
    /** [text] sorted, or null when every section is already in order. */
    fun sort(text: String): String? {
        val lines = text.split("\n")
        val starts = lines.indices.filter { it > 0 && HEADING.containsMatchIn(lines[it]) }
        val bounds = listOf(0) + starts + lines.size
        var changed = false
        val out = ArrayList<String>(lines.size)
        for (i in 0 until bounds.size - 1) {
            val section = lines.subList(bounds[i], bounds[i + 1])
            val sorted = sortSection(section)
            if (sorted != null) changed = true
            out += sorted ?: section
        }
        return if (changed) out.joinToString("\n") else null
    }

    /** One section's lines sorted, or null when they already are. */
    private fun sortSection(lines: List<String>): List<String>? {
        // Each slot is a task block or a run of other lines, in file order.
        val slots = mutableListOf<Slot>()
        var at = 0
        while (at < lines.size) {
            val status = ROOT_TASK.find(lines[at])?.groupValues?.get(1)
            if (status != null) {
                var end = at + 1
                while (end < lines.size && lines[end].isChild()) end++
                slots += Slot(lines.subList(at, end), task = true, done = status != " ")
                at = end
            } else {
                var end = at + 1
                while (end < lines.size && ROOT_TASK.find(lines[end]) == null) end++
                slots += Slot(lines.subList(at, end), task = false, done = false)
                at = end
            }
        }
        val tasks = slots.filter { it.task }
        val ordered = tasks.filter { it.done } + tasks.filterNot { it.done }
        if (ordered == tasks) return null
        val next = ordered.iterator()
        return slots.flatMap { if (it.task) next.next().lines else it.lines }
    }

    private fun String.isChild(): Boolean = startsWith("    ") || startsWith("\t")

    /** Compared by identity: two blocks with the same words are still two blocks. */
    private class Slot(
        val lines: List<String>,
        val task: Boolean,
        val done: Boolean,
    )

    private val HEADING = Regex("^#{1,6} ")
    private val ROOT_TASK = Regex("""^- \[([ xX\-])\] """)
}
