package me.parham1995.notes.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

data class TasksUiState(
    val groups: List<TaskGroup> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = true,
) {
    val overdue: Int get() = groups.firstOrNull { it.bucket == TaskBucket.OVERDUE }?.rows?.size ?: 0
}

@HiltViewModel
class TasksViewModel
    @Inject
    constructor(
        repository: VaultRepository,
    ) : ViewModel() {
        val state: StateFlow<TasksUiState> =
            repository
                .openTasks()
                .map { rows ->
                    // The day is read here rather than held, so leaving the app
                    // open overnight does not leave yesterday's "Today" on
                    // screen for the whole of the next day.
                    val today = LocalDate.now()
                    val grouped = rows.groupBy { TaskBuckets.of(it.actionableOn, today) }
                    TasksUiState(
                        groups =
                            TaskBucket.entries
                                .mapNotNull { bucket ->
                                    grouped[bucket]?.takeIf { it.isNotEmpty() }?.let { TaskGroup(bucket, it) }
                                },
                        total = rows.size,
                        loading = false,
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
