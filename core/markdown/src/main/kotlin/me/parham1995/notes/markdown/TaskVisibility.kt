package me.parham1995.notes.markdown

/**
 * Whether a task is finished with, in the sense the task list uses: ticked or
 * cancelled. Both are things no longer to be done.
 */
val TaskState.isFinished: Boolean get() = this == TaskState.CHECKED || this == TaskState.CANCELLED

/**
 * Whether this item, or anything nested under it, is still to be done.
 *
 * The reason a finished item cannot simply be hidden. This vault nests sub-tasks
 * one level under their parent, and a parent is routinely ticked off while a
 * sub-task under it is not -- hiding the parent would take the open sub-task off
 * the screen with it. Losing open work is the one failure that would make a
 * "hide completed" setting untrustworthy, so hiding stops at any item with
 * something live underneath.
 */
fun MdListItem.hasOpenTask(): Boolean = !task.isFinished && task != TaskState.NONE || blocks.any { it.hasOpenTask() }

/** Whether the setting may hide this item: finished, and hiding nothing live. */
fun MdListItem.isFinishedAndEmpty(): Boolean = task.isFinished && !hasOpenTask()

private fun MdBlock.hasOpenTask(): Boolean =
    when (this) {
        is MdBlock.ListBlock -> items.any { it.hasOpenTask() }
        is MdBlock.Callout -> children.any { it.hasOpenTask() }
        is MdBlock.Quote -> children.any { it.hasOpenTask() }
        else -> false
    }
