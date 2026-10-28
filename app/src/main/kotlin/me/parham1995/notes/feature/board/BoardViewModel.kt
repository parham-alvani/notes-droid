package me.parham1995.notes.feature.board

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.parham1995.notes.data.ObsidianConfigStore
import me.parham1995.notes.data.Rescheduling
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.database.QueryTaskRow
import me.parham1995.notes.feature.tasks.TaskSource
import me.parham1995.notes.feature.tasks.describe
import me.parham1995.notes.feature.tasks.describeMove
import me.parham1995.notes.feature.tasks.taskSources
import me.parham1995.notes.markdown.TaskBoard
import me.parham1995.notes.markdown.TaskState
import me.parham1995.notes.markdown.TaskStatuses
import me.parham1995.notes.ui.UiText
import java.time.LocalDate
import javax.inject.Inject

data class BoardUiState(
    val columns: List<TaskBoard.Column<QueryTaskRow>> = emptyList(),
    val grouping: TaskBoard.Grouping = TaskBoard.Grouping.STATUS,
    /** Every done and cancelled task, not only the last two weeks'. */
    val allClosed: Boolean = false,
    /** Every file holding a task, for the filter, with its open count. */
    val sources: List<TaskSource> = emptyList(),
    val selectedPath: String? = null,
    /** Open tasks on the board as filtered. */
    val open: Int = 0,
    /** Whether this vault's credential can push, and an author is set. */
    val canWrite: Boolean = false,
    val loading: Boolean = true,
    val message: UiText? = null,
    val undo: Rescheduling.Undo? = null,
    /** The vault's task statuses, from its Tasks plugin settings. */
    val statuses: TaskStatuses = TaskStatuses.DEFAULT,
)

/**
 * What the board needs to know about a row, read off the index's columns; its
 * status is what [statuses] says the character means.
 */
internal fun QueryTaskRow.facts(statuses: TaskStatuses = TaskStatuses.DEFAULT): TaskBoard.Facts =
    TaskBoard.Facts(
        state = TaskState.entries.firstOrNull { it.name == state } ?: TaskState.UNCHECKED,
        status = statuses.of(symbol),
        reason = reason,
        path = notePath,
        title = noteTitle,
        line = line,
        day = actionableOn,
        priority = priority,
        done = done,
        cancelled = cancelled,
    )

/**
 * The active vault's tasks as a board. Every query is the active vault's;
 * every move goes through [VaultWriteRepository], and is offered only where
 * it can push and an author is set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BoardViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val writes: VaultWriteRepository,
        private val savedState: SavedStateHandle,
        config: ObsidianConfigStore,
    ) : ViewModel() {
        private val statuses: StateFlow<TaskStatuses> =
            config
                .taskStatuses(repository.activeVaultId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, TaskStatuses.DEFAULT)

        private data class Notice(
            val text: UiText,
            val undo: Rescheduling.Undo? = null,
        )

        private val message = MutableStateFlow<Notice?>(null)

        fun dismissMessage() {
            message.value = null
        }

        private val grouping: StateFlow<String> = savedState.getStateFlow(KEY_GROUPING, TaskBoard.Grouping.STATUS.name)
        private val allClosed: StateFlow<Boolean> = savedState.getStateFlow(KEY_ALL_CLOSED, false)
        private val selected: StateFlow<String?> = savedState.getStateFlow(KEY_FILE, null)

        fun groupBy(by: TaskBoard.Grouping) {
            savedState[KEY_GROUPING] = by.name
        }

        fun showAllClosed(all: Boolean) {
            savedState[KEY_ALL_CLOSED] = all
        }

        fun filterBy(notePath: String?) {
            savedState[KEY_FILE] = notePath
        }

        /** [row] moved to the column that writes [drop]. */
        fun move(
            row: QueryTaskRow,
            drop: TaskBoard.Drop,
            reason: String? = null,
        ) = viewModelScope.launch {
            val task = row.toTaskRow()
            message.value =
                when (drop) {
                    // A card already of the column's type keeps its own
                    // status: `[r]` on In Progress stays "In Review".
                    is TaskBoard.Drop.Status -> {
                        val status =
                            TaskBoard.statusFor(row.facts(statuses.value), drop, statuses.value) ?: return@launch
                        Notice(writes.setTaskStatus(task, status, reason).describe())
                    }
                    is TaskBoard.Drop.Day -> {
                        val date = drop.date
                        if (date == null) {
                            Notice(writes.rescheduleTask(task, null, undated = true).result.describe())
                        } else {
                            val moved = writes.rescheduleTask(task, date)
                            Notice(moved.result.describeMove(date), moved.undo)
                        }
                    }
                }
        }

        /** The checkbox: an open task is ticked as it is everywhere, a closed one opened again. */
        fun toggle(row: QueryTaskRow) =
            viewModelScope.launch {
                val task = row.toTaskRow()
                val result =
                    if (row.facts(statuses.value).isOpen) writes.completeTask(task) else writes.setTaskStatus(task, ' ')
                message.value = Notice(result.describe())
            }

        /** [row] set to [status] from the card's menu, with the reason a cancel asked for. */
        fun setStatus(
            row: QueryTaskRow,
            status: Char,
            reason: String?,
        ) = viewModelScope.launch {
            message.value = Notice(writes.setTaskStatus(row.toTaskRow(), status, reason).describe())
        }

        /** [row]'s reason written, changed, or cleared when blank. */
        fun setReason(
            row: QueryTaskRow,
            reason: String,
        ) = viewModelScope.launch {
            message.value = Notice(writes.setTaskReason(row.toTaskRow(), reason).describe())
        }

        fun undo(undo: Rescheduling.Undo) =
            viewModelScope.launch {
                message.value = Notice(writes.rescheduleTask(undo.task, undo.date).result.describe())
            }

        val state: StateFlow<BoardUiState> =
            combine(
                repository.activeVaultId.flatMapLatest { repository.queryableTasks(it) },
                grouping,
                allClosed,
                selected,
                statuses,
            ) { rows, by, all, chosen, known ->
                // Read here, not held: a board left open overnight moves on.
                val today = LocalDate.now()
                val grouped = TaskBoard.Grouping.entries.firstOrNull { it.name == by } ?: TaskBoard.Grouping.STATUS
                val openByPath =
                    rows.groupBy { it.notePath }.mapValues { (_, theirs) ->
                        theirs.count { it.facts(known).isOpen }
                    }
                val sources = taskSources(openByPath)
                val chosenPath = chosen?.takeIf { path -> sources.any { it.path == path } }
                val visible = chosenPath?.let { path -> rows.filter { it.notePath == path } } ?: rows
                val columns =
                    TaskBoard.columns(
                        visible,
                        grouped,
                        today,
                        closedWithin = if (all) null else TaskBoard.CLOSED_WITHIN,
                    ) { it.facts(known) }
                BoardUiState(
                    columns = columns,
                    grouping = grouped,
                    allClosed = all,
                    sources = sources,
                    selectedPath = chosenPath,
                    open = visible.count { it.facts(known).isOpen },
                    loading = false,
                    statuses = known,
                )
            }.let { base ->
                combine(base, writes.writable, repository.activeVaultId, message) { ui, writable, activeId, said ->
                    ui.copy(canWrite = activeId in writable, message = said?.text, undo = said?.undo)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = BoardUiState(),
            )

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
            const val KEY_GROUPING = "board_grouping"
            const val KEY_ALL_CLOSED = "board_all_closed"
            const val KEY_FILE = "board_file"
        }
    }
