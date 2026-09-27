package me.parham1995.notes.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A reminder about one task, at a time of day.
 *
 * The Tasks plugin writes dates, never times, so a reminder is kept on the
 * phone rather than written into the note: it is this phone's to ring, and a
 * time in the markdown would be syntax no other tool reads. The task is named
 * the way a pin names a note -- vault, path, and its line and text as the
 * index recorded them -- because task ids are reissued by every reindex.
 */
data class TaskReminder(
    val id: Long,
    val vaultId: Long,
    val path: String,
    val noteTitle: String,
    val text: String,
    val line: Int,
    /** When to ring, in epoch milliseconds. */
    val at: Long,
)

/**
 * The reminders set and not yet rung. Setting and ringing them is the app's
 * (the alarm and the notification are screens' business); this only keeps
 * the list, so it survives a reboot and can be shown and cancelled.
 */
@Singleton
class TaskReminders
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val preferences = context.getSharedPreferences("task_reminders", Context.MODE_PRIVATE)
        private val _all = MutableStateFlow(decode(preferences.getString(KEY, null)))

        /** Every reminder waiting, soonest first. */
        val all: StateFlow<List<TaskReminder>> = _all.asStateFlow()

        fun byId(id: Long): TaskReminder? = _all.value.firstOrNull { it.id == id }

        /** Keeps [reminder] under a fresh id, and returns it with that id. */
        @Synchronized
        fun add(reminder: TaskReminder): TaskReminder {
            val id = (_all.value.maxOfOrNull { it.id } ?: 0L) + 1
            val kept = reminder.copy(id = id)
            save((_all.value + kept).sortedBy { it.at })
            return kept
        }

        @Synchronized
        fun remove(id: Long) = save(_all.value.filterNot { it.id == id })

        private fun save(reminders: List<TaskReminder>) {
            _all.value = reminders
            preferences.edit { putString(KEY, encode(reminders)) }
        }

        companion object {
            private const val KEY = "reminders"

            internal fun encode(reminders: List<TaskReminder>): String =
                JSONArray(
                    reminders.map {
                        JSONObject()
                            .put("id", it.id)
                            .put("vault", it.vaultId)
                            .put("path", it.path)
                            .put("title", it.noteTitle)
                            .put("text", it.text)
                            .put("line", it.line)
                            .put("at", it.at)
                    },
                ).toString()

            /** What was stored; anything unreadable is dropped rather than failing the rest. */
            internal fun decode(stored: String?): List<TaskReminder> {
                if (stored.isNullOrBlank()) return emptyList()
                val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
                return (0 until array.length())
                    .mapNotNull { index ->
                        runCatching {
                            val it = array.getJSONObject(index)
                            TaskReminder(
                                id = it.getLong("id"),
                                vaultId = it.getLong("vault"),
                                path = it.getString("path"),
                                noteTitle = it.optString("title"),
                                text = it.getString("text"),
                                line = it.optInt("line", -1),
                                at = it.getLong("at"),
                            )
                        }.getOrNull()
                    }.sortedBy { it.at }
            }
        }
    }
