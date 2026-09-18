package me.parham1995.notes.markdown

/**
 * One task, lifted out of a note.
 *
 * The dates are separated out by meaning rather than kept as the emoji pairs
 * they were written as, because everything a task list wants to do -- sort,
 * decide that something is overdue, group by week -- needs to know which of the
 * seven possible dates on a line it is reading.
 */
data class ParsedTask(
    val text: String,
    val state: TaskState,
    /** The nearest heading above it, which in this vault is the project. */
    val section: String,
    /** Index into the note's blocks, so opening the note can scroll to it. */
    val blockIndex: Int,
    val ordinal: Int,
    val scheduled: String? = null,
    val due: String? = null,
    val start: String? = null,
    val done: String? = null,
    val cancelled: String? = null,
    val recurring: String? = null,
) {
    /** Still worth showing in a list of things to do. */
    val isOpen: Boolean get() = state == TaskState.UNCHECKED || state == TaskState.IN_PROGRESS

    /**
     * The date this task is answerable on.
     *
     * Due wins where both are set, but this vault almost never sets one: 776
     * tasks carry a scheduled date and nine carry a due date, because the
     * convention here is to schedule work rather than to promise it. Reading
     * only the due date would show an empty list.
     */
    val actionableOn: String? get() = due ?: scheduled
}

/**
 * Pulls the tasks out of a parsed note.
 *
 * Tasks are found wherever they are, not only at the top level: this vault
 * nests sub-tasks one level under their parent, and a task inside a callout is
 * still a task. What is kept is the *top-level* block index, because that is
 * what the reader can scroll to.
 */
object TaskExtractor {
    fun extract(note: ParsedNote): List<ParsedTask> {
        val out = mutableListOf<ParsedTask>()
        note.blocks.forEachIndexed { index, block ->
            collect(block, section = sectionAt(note, index), blockIndex = index, into = out)
        }
        return out.mapIndexed { ordinal, task -> task.copy(ordinal = ordinal) }
    }

    /** The last heading at or above this block. */
    private fun sectionAt(
        note: ParsedNote,
        blockIndex: Int,
    ): String =
        note.headings
            .lastOrNull { it.blockIndex <= blockIndex }
            ?.text
            .orEmpty()

    private fun collect(
        block: MdBlock,
        section: String,
        blockIndex: Int,
        into: MutableList<ParsedTask>,
    ) {
        when (block) {
            is MdBlock.ListBlock ->
                block.items.forEach { item ->
                    if (item.task != TaskState.NONE) {
                        into += item.toTask(section, blockIndex)
                    }
                    // A sub-task is a task in its own right -- this vault's own
                    // rule says so -- so nested lists are walked rather than
                    // flattened away.
                    item.blocks.forEach { collect(it, section, blockIndex, into) }
                }

            is MdBlock.Callout -> block.children.forEach { collect(it, section, blockIndex, into) }
            is MdBlock.Quote -> block.children.forEach { collect(it, section, blockIndex, into) }
            else -> Unit
        }
    }

    private fun MdListItem.toTask(
        section: String,
        blockIndex: Int,
    ): ParsedTask {
        val dates = taskMeta.associate { (TaskMetadata.meaningOf(it.emoji) ?: it.emoji) to it.value }
        return ParsedTask(
            text = text().trim(),
            state = task,
            section = section,
            blockIndex = blockIndex,
            ordinal = 0,
            scheduled = dates["scheduled"],
            due = dates["due"],
            start = dates["start"],
            done = dates["done"],
            cancelled = dates["cancelled"],
            recurring = dates["recurring"],
        )
    }

    /**
     * The item's own text, without anything nested under it. A parent task
     * whose sub-tasks were folded in would read as one enormous line.
     */
    private fun MdListItem.text(): String =
        blocks
            .filterIsInstance<MdBlock.Paragraph>()
            .joinToString(" ") { paragraph -> paragraph.inlines.plain() }

    private fun List<MdInline>.plain(): String =
        joinToString("") { node ->
            when (node) {
                is MdInline.Text -> node.text
                is MdInline.Code -> node.code
                is MdInline.WikiLink -> node.alias ?: node.target
                is MdInline.Link -> node.children.plain()
                is MdInline.Emphasis -> node.children.plain()
                is MdInline.Strong -> node.children.plain()
                is MdInline.Highlight -> node.children.plain()
                is MdInline.Strikethrough -> node.children.plain()
                else -> ""
            }
        }
}
