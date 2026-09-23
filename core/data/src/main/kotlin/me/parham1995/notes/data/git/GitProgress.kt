package me.parham1995.notes.data.git

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.jgit.lib.ProgressMonitor

/**
 * Reports what a clone or fetch is doing.
 *
 * Without it a clone is completely silent: JGit counts objects, downloads a
 * pack and checks out thousands of files while the app shows nothing, which is
 * indistinguishable from a hang. A full clone moves well over a hundred
 * megabytes, so "slow and silent" is the normal case.
 *
 * Publishing through a [StateFlow] rather than calling back into a suspending
 * logger is deliberate. JGit invokes these methods from its own transport
 * thread, and the previous version bridged that with `runBlocking`, which
 * blocks a dispatcher thread on every stage change while a database write is
 * dispatched elsewhere -- a plausible way to deadlock the very operation it was
 * meant to narrate. Assigning to a StateFlow cannot block.
 */
class GitProgress : ProgressMonitor {
    /** The coroutine the current operation runs for; see [bindTo]. */
    @Volatile
    private var job: Job? = null

    /**
     * Ties JGit's own cancellation to [job]'s.
     *
     * A clone or fetch is a blocking call that a coroutine cannot interrupt,
     * and JGit asks this monitor between steps whether to stop. It used to
     * answer no, always, so cancelling a sync -- the button, WorkManager, a
     * newer sync replacing it -- left the transfer running to the end in the
     * background, holding the vault's lock, however long that was.
     */
    fun bindTo(job: Job?) {
        this.job = job
    }

    private val _stage = MutableStateFlow("")

    /** The current stage, e.g. `Receiving objects (1204/3301)`. */
    val stage: StateFlow<String> = _stage.asStateFlow()

    private var task: String = ""
    private var total: Int = 0
    private var done: Int = 0
    private var lastUpdate: Long = 0

    override fun start(totalTasks: Int) = Unit

    override fun beginTask(
        title: String?,
        totalWork: Int,
    ) {
        task = title.orEmpty().trim()
        total = totalWork
        done = 0
        lastUpdate = 0
        publish()
    }

    override fun update(completed: Int) {
        done += completed
        val now = System.currentTimeMillis()
        // JGit calls this per object; a vault has thousands.
        if (now - lastUpdate < THROTTLE_MS) return
        lastUpdate = now
        publish()
    }

    override fun endTask() {
        if (task.isNotEmpty()) _stage.value = "$task - done"
        task = ""
    }

    override fun isCancelled(): Boolean = job?.isCancelled == true

    override fun showDuration(enabled: Boolean) = Unit

    private fun publish() {
        if (task.isEmpty()) return
        _stage.value = if (total > 0) "$task ($done/$total)" else task
    }

    private companion object {
        const val THROTTLE_MS = 1_500L
    }
}
