package me.parham1995.notes.feature.note

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One open note. The title is carried so the strip can be drawn before it loads. */
data class NoteTab(
    val noteId: Long,
    val title: String = "",
)

data class TabsState(
    val tabs: List<NoteTab> = emptyList(),
    val active: Int = 0,
) {
    val current: NoteTab? get() = tabs.getOrNull(active)
}

/**
 * The notes that are open at once.
 *
 * A vault is read by holding two or three notes at a time -- the thing being
 * read, the thing it links to, and the index they both hang off -- and a back
 * stack makes that a straight line you can only walk backwards. Tabs make it a
 * set you can move around in.
 *
 * A singleton rather than something scoped to a screen, because the point of a
 * tab is that it is still there after going to search, opening a task, and
 * coming back. Held in memory only: what survives a process death is each
 * note's own scroll position, which is in the database already, so a restarted
 * app opens the note it was on rather than a set of tabs whose order nobody
 * remembers.
 */
@Singleton
class NoteTabs
    @Inject
    constructor() {
        private val _state = MutableStateFlow(TabsState())
        val state: StateFlow<TabsState> = _state.asStateFlow()

        /**
         * Shows [noteId], in a new tab or in the one being looked at.
         *
         * A note that is already open is switched to rather than opened twice;
         * two tabs of the same note is never what was meant, and it is the
         * easy way to end up with nine of them.
         */
        fun open(
            noteId: Long,
            inNewTab: Boolean,
        ) {
            _state.update { state ->
                val existing = state.tabs.indexOfFirst { it.noteId == noteId }
                when {
                    existing >= 0 -> state.copy(active = existing)
                    state.tabs.isEmpty() -> TabsState(listOf(NoteTab(noteId)), 0)
                    inNewTab -> {
                        // Beside the one it came from, the way a browser does
                        // it: a link opened from tab two belongs next to tab
                        // two, not at the end of a row of nine.
                        val at = (state.active + 1).coerceAtMost(state.tabs.size)
                        state.copy(
                            tabs = state.tabs.toMutableList().apply { add(at, NoteTab(noteId)) },
                            active = at,
                        )
                    }
                    else ->
                        state.copy(
                            tabs = state.tabs.toMutableList().apply { this[state.active] = NoteTab(noteId) },
                        )
                }
            }
        }

        fun select(index: Int) {
            _state.update { if (index in it.tabs.indices) it.copy(active = index) else it }
        }

        /** Closes a tab, and returns false when that was the last one. */
        fun close(index: Int): Boolean {
            var anyLeft = true
            _state.update { state ->
                if (index !in state.tabs.indices) return@update state
                val tabs = state.tabs.toMutableList().apply { removeAt(index) }
                anyLeft = tabs.isNotEmpty()
                TabsState(
                    tabs = tabs,
                    // Stay where you were looking: closing a tab to the left
                    // should not move the one being read out from under you.
                    active = if (index < state.active) state.active - 1 else state.active.coerceAtMost(tabs.size - 1),
                )
            }
            return anyLeft
        }

        fun retitle(
            noteId: Long,
            title: String,
        ) {
            _state.update { state ->
                state.copy(tabs = state.tabs.map { if (it.noteId == noteId) it.copy(title = title) else it })
            }
        }

        /** Everything closed, for leaving the reader entirely. */
        fun clear() {
            _state.value = TabsState()
        }
    }

private fun MutableStateFlow<TabsState>.update(block: (TabsState) -> TabsState) {
    value = block(value)
}
