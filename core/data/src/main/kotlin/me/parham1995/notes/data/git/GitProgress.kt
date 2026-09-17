package me.parham1995.notes.data.git

import org.eclipse.jgit.lib.ProgressMonitor

/**
 * Reports what a clone or fetch is doing.
 *
 * Without this a clone is completely silent: JGit counts objects, downloads a
 * pack and checks out thousands of files while the app shows nothing at all,
 * which is indistinguishable from a hang. A full clone of a real vault moves
 * over a hundred megabytes, so "slow and silent" is the normal case, not the
 * exception.
 *
 * Updates are throttled -- JGit calls [update] per object, which for a vault of
 * a few thousand files would otherwise mean thousands of writes to the journal.
 */
class GitProgress(
    private val onStage: (String) -> Unit,
    private val onProgress: (task: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
) : ProgressMonitor {
    private var task: String = ""
    private var total: Int = 0
    private var done: Int = 0
    private var lastReported: Long = 0

    override fun start(totalTasks: Int) = Unit

    override fun beginTask(
        title: String?,
        totalWork: Int,
    ) {
        task = title.orEmpty()
        total = totalWork
        done = 0
        lastReported = 0
        onStage(if (totalWork > 0) "$task (0/$totalWork)" else task)
    }

    override fun update(completed: Int) {
        done += completed
        val now = System.currentTimeMillis()
        if (now - lastReported < THROTTLE_MS) return
        lastReported = now
        onProgress(task, done, total)
    }

    override fun endTask() {
        if (task.isNotEmpty()) onStage("$task done" + if (total > 0) " ($done/$total)" else "")
        task = ""
    }

    override fun isCancelled(): Boolean = false

    override fun showDuration(enabled: Boolean) = Unit

    private companion object {
        const val THROTTLE_MS = 1_000L
    }
}
