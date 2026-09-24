package me.parham1995.notes.markdown

/**
 * One tag in the tree: `project` holding `project/alpha` holding
 * `project/alpha/q3`.
 *
 * [path] is the whole tag, which is what tapping it asks for; [label] is the
 * last part of it, which is what a nested row shows. [count] is the notes
 * carrying this tag or any tag under it, each once.
 */
data class TagTreeNode(
    val path: String,
    val label: String,
    val count: Int,
    val children: List<TagTreeNode>,
)

object TagTree {
    /**
     * Builds the tree from every (tag, note) pair in a vault.
     *
     * A parent's count is a set, not a sum: a note tagged both `a/b` and `a/c`
     * is one note under `a`. A parent that nobody tags directly still appears,
     * because it is how its children are reached.
     *
     * Case-insensitive, as Obsidian is; each tag is spelled as it was first met.
     */
    fun build(uses: List<Pair<String, Long>>): List<TagTreeNode> {
        val notes = HashMap<String, MutableSet<Long>>()
        val spelling = HashMap<String, String>()
        uses.forEach { (tag, noteId) ->
            Tags.ancestry(tag).forEach { path ->
                val key = Slugs.fold(path)
                spelling.putIfAbsent(key, path)
                notes.getOrPut(key) { HashSet() } += noteId
            }
        }

        val byParent = notes.keys.groupBy { it.substringBeforeLast('/', "") }

        // A child is spelled under its parent's spelling, so `Idea` and
        // `IDEA/sub` read as one tag and its child rather than two.
        fun nodes(
            parent: String,
            parentPath: String,
        ): List<TagTreeNode> =
            byParent[parent]
                .orEmpty()
                .map { key ->
                    val label = spelling.getValue(key).substringAfterLast('/')
                    val path = if (parentPath.isEmpty()) label else "$parentPath/$label"
                    TagTreeNode(
                        path = path,
                        label = label,
                        count = notes.getValue(key).size,
                        children = nodes(key, path),
                    )
                }.sortedWith(compareBy({ Slugs.fold(it.label) }, { it.label }))

        return nodes("", "")
    }

    /** The tree as rows, each with how deep it is -- what a list draws. */
    fun flatten(
        nodes: List<TagTreeNode>,
        depth: Int = 0,
    ): List<Pair<TagTreeNode, Int>> = nodes.flatMap { listOf(it to depth) + flatten(it.children, depth + 1) }
}
