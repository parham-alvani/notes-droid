package me.parham1995.notes.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.TaskBucket
import me.parham1995.notes.data.TaskBuckets
import me.parham1995.notes.data.VaultRepository
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
) {
    val overdue: Int get() = groups.firstOrNull { it.bucket == TaskBucket.OVERDUE }?.rows?.size ?: 0
}

@HiltViewModel
class TasksViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
    ) : ViewModel() {
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
                val sources =
                    rows
                        .groupingBy { it.notePath }
                        .eachCount()
                        .map { (path, count) ->
                            TaskSource(path, path.substringAfterLast('/').removeSuffix(".md"), count)
                        }.sortedBy { it.name.lowercase() }
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
                    selectedPath = chosenPath,
                    vaultLabel = vaults.firstOrNull { it.id == activeId }?.label.orEmpty(),
                    elsewhere =
                        vaults
                            .filter { it.id != activeId }
                            .mapNotNull { vault ->
                                counts[vault.id]?.takeIf { it > 0 }?.let { VaultTasks(vault.id, vault.label, it) }
                            },
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = TasksUiState(),
            )

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
