package me.parham1995.notes.reminder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.parham1995.notes.data.TaskReminder
import me.parham1995.notes.data.TaskReminders
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.TaskRow
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/** Setting, listing and cancelling task reminders, for any screen that shows tasks. */
@HiltViewModel
class ReminderViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val reminders: Reminders,
        store: TaskReminders,
        private val repository: VaultRepository,
    ) : ViewModel() {
        /** What is still to ring, soonest first. */
        val upcoming: StateFlow<List<TaskReminder>> = store.all

        fun remind(
            task: TaskRow,
            at: LocalDateTime,
        ) {
            reminders.set(
                context,
                TaskReminder(
                    id = 0,
                    vaultId = task.vaultId,
                    path = task.notePath,
                    noteTitle = task.noteTitle,
                    text = task.text,
                    line = task.line,
                    at = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                ),
            )
        }

        /** The task written on [line] of [noteId], found the way ticking it finds it. */
        fun remindInNote(
            noteId: Long,
            line: Int,
            at: LocalDateTime,
            /** Whether the task was found, and so whether a reminder was set. */
            onResult: (Boolean) -> Unit,
        ) = viewModelScope.launch {
            val task = repository.tasksIn(noteId).firstOrNull { it.line == line }
            task?.let { remind(it, at) }
            onResult(task != null)
        }

        fun cancel(id: Long) = reminders.cancel(context, id)
    }

/** A reminder's time as the phone writes dates and times, in its own language. */
fun LocalDateTime.forReading(): String =
    format(
        java.time.format.DateTimeFormatter
            .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            .withLocale(java.util.Locale.getDefault()),
    )
