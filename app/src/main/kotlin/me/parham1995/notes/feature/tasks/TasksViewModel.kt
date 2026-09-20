package me.parham1995.notes.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.TaskBucket
import me.parham1995.notes.data.TaskBuckets
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.data.database.TaskRow
import java.time.LocalDate
import javax.inject.Inject

data class TaskGroup(
    val bucket: TaskBucket,
    val rows: List<TaskRow>,
)

/** A file with open tasks in it, named the way the browser names it. */
data class TaskSource(
    val path: String,
    val name: String,
    val count: Int,
)

/** A vault that has open tasks, for offering to switch to it. */
data class VaultTasks(
    val id: Long,
    val label: String,
    val count: Int,
)

data class TasksUiState(
    val groups: List<TaskGroup> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
    /** The vault being read, so an empty list can say which one it means. */
    val vaultLabel: String = "",
    /** Vaults that do have open tasks, when this one has none. */
    val elsewhere: List<VaultTasks> = emptyList(),
    /** Every file holding an open task, for the filter. */
    val sources: List<TaskSource> = emptyList(),
    /** The file being filtered to, or null for all of them. */
    val selectedPath: String? = null,
    /** Whether this vault's credential can push, and an author is set. */
    val canWrite: Boolean = false,
    /** The headings each file already uses, so a new task can join one. */
    val sectionsByPath: Map<String, List<String>> = emptyMap(),
    /** The last thing a write had to say, shown once and dismissed. */
    val message: String? = null,
    /**
     * Edits made here that have not reached the repository.
     *
     * Shown because otherwise a queued edit is invisible: it is said once in a
     * snackbar and then only exists in a settings screen nobody opens. You
     * would think you had ticked something off and never learn that you had
     * not.
     */
    val waiting: Int = 0,
) {
    val overdue: Int get() = groups.firstOrNull { it.bucket == TaskBucket.OVERDUE }?.rows?.size ?: 0
}

@HiltViewModel
class TasksViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val writes: VaultWriteRepository,
    ) : ViewModel() {
        private val message = MutableStateFlow<String?>(null)

        fun dismissMessage() {
            message.value = null
        }

        /**
         * Ticks a task and sends it, reporting what happened either way.
         *
         * Reported rather than assumed: the same tap can land in the repository,
         * sit in a queue with no signal, or be refused outright because the task
         * repeats -- and a checkbox that just changes colour says none of that.
         */
        fun complete(row: TaskRow) =
            viewModelScope.launch {
                message.value = writes.completeTask(row).describe()
            }

        fun addTask(
            path: String,
            section: String,
            text: String,
        ) = viewModelScope.launch {
            val vaultId = repository.activeVaultId.first()
            message.value = writes.addTask(vaultId, path, section, text).describe()
        }

        fun switchTo(vaultId: Long) {
            viewModelScope.launch { repository.setActiveVault(vaultId) }
        }

        /**
         * Which file's tasks to show, or null for all of them.
         *
         * Held here rather than in the screen so it survives the screen being
         * left and returned to, which happens every time a task is opened.
         */
        private val selected = MutableStateFlow<String?>(null)

        fun filterBy(notePath: String?) {
            selected.value = notePath
        }

        val state: StateFlow<TasksUiState> =
            combine(
                repository.openTasks(),
                repository.vaults(),
                repository.activeVaultId,
                repository.openTaskCounts(),
                selected,
            ) { rows, vaults, activeId, counts, chosen ->
                // The day is read here rather than held, so leaving the app
                // open overnight does not leave yesterday's "Today" on
                // screen for the whole of the next day.
                val today = LocalDate.now()
                // Built from the unfiltered rows: a filter assembled from what
                // it already filtered would leave one file to choose from.
                val sources = taskSources(rows.groupingBy { it.notePath }.eachCount())
                // A file whose last task is ticked off stops being a filter
                // anyone can act on, and leaving it selected is an empty
                // screen with nothing saying why.
                val chosenPath = chosen?.takeIf { path -> sources.any { it.path == path } }
                val visible = chosenPath?.let { path -> rows.filter { it.notePath == path } } ?: rows
                val grouped = visible.groupBy { TaskBuckets.of(it.actionableOn, today) }
                TasksUiState(
                    groups =
                        TaskBucket.entries
                            .mapNotNull { bucket ->
                                grouped[bucket]?.takeIf { it.isNotEmpty() }?.let { TaskGroup(bucket, it) }
                            },
                    total = visible.size,
                    loading = false,
                    sources = sources,
                    sectionsByPath =
                        rows
                            .groupBy { it.notePath }
                            .mapValues { (_, theirs) ->
                                theirs.map { it.section }.filter { it.isNotBlank() }.distinct()
                            },
                    selectedPath = chosenPath,
                    vaultLabel = vaults.firstOrNull { it.id == activeId }?.label.orEmpty(),
                    elsewhere =
                        vaults
                            .filter { it.id != activeId }
                            .mapNotNull { vault ->
                                counts[vault.id]?.takeIf { it > 0 }?.let { VaultTasks(vault.id, vault.label, it) }
                            },
                )
            }.let { base ->
                // Nested rather than more arguments: `combine` is typed up to
                // five and the vararg form loses every type in the lambda.
                combine(
                    base,
                    writes.writable,
                    repository.activeVaultId,
                    message,
                    writes.queued,
                ) { ui, writable, activeId, said, queued ->
                    ui.copy(canWrite = activeId in writable, message = said, waiting = queued)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = TasksUiState(),
            )

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }

/**
 * Names the files in the filter, telling apart the ones that share a name.
 *
 * This vault has 110 duplicated basenames in it, and the filter showed two
 * entries reading "Best Practices" with different counts and no way to know
 * which was which. A repeated name is qualified by the folder holding it,
 * which is what the author would have called it anyway; a unique one is left
 * alone, because "Tasks / Nobitex" is noise when there is only one Nobitex.
 */
internal fun taskSources(countsByPath: Map<String, Int>): List<TaskSource> {
    fun base(path: String) = path.substringAfterLast('/').removeSuffix(".md")

    val sharing = countsByPath.keys.groupingBy(::base).eachCount()
    return countsByPath
        .map { (path, count) ->
            val name = base(path)
            val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
            val label = if ((sharing[name] ?: 0) > 1 && folder.isNotBlank()) "$folder / $name" else name
            TaskSource(path, label, count)
        }.sortedBy { it.name.lowercase() }
}

/** What to say about a write, in one line. */
private fun WriteResult.describe(): String =
    when (this) {
        WriteResult.Pushed -> "Saved"
        is WriteResult.Queued -> "Saved here - $why"
        WriteResult.Unchanged -> "Already done"
        is WriteResult.Refused -> why
    }
