package me.parham1995.notes.feature.note

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One open note, and how it was arrived at.
 *
 * A tab keeps its own trail rather than sharing one with the others, because
 * following three links in one tab and two in another leaves a single back
 * stack that walks out of a tab you were not in.
 */
data class NoteTab(
    val history: List<Long>,
    val index: Int = history.lastIndex,
    val title: String = "",
) {
    val noteId: Long get() = history[index]

    /** Whether there is somewhere in this tab to go back to. */
    val canGoBack: Boolean get() = index > 0
}

data class TabsState(
    val tabs: List<NoteTab> = emptyList(),
    val active: Int = 0,
) {
    val current: NoteTab? get() = tabs.getOrNull(active)
}

/**
 * The notes that are open at once, and where each one has been.
 *
 * A vault is read by holding two or three notes at a time -- the thing being
 * read, the thing it links to, and the index they both hang off -- and one
 * back stack makes that a straight line you can only walk backwards.
 *
 * A singleton rather than something scoped to a screen, because the point of a
 * tab is that it is still there after going to search and coming back. Held in
 * memory only: what survives a process death is each note's own scroll
 * position, which is in the database already.
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
         * Opening it where you are pushes onto that tab's trail, the way
         * following a link does. A note already showing in some tab is
         * switched to instead: two tabs of one note is never what was meant.
         */
        fun open(
            noteId: Long,
            inNewTab: Boolean,
        ) = update { state ->
            val showing = state.tabs.indexOfFirst { it.noteId == noteId }
            when {
                showing >= 0 -> state.copy(active = showing)
                state.tabs.isEmpty() -> TabsState(listOf(NoteTab(listOf(noteId))), 0)
                inNewTab -> {
                    // Beside the one it came from, the way a browser does it.
                    val at = (state.active + 1).coerceAtMost(state.tabs.size)
                    state.copy(
                        tabs = state.tabs.toMutableList().apply { add(at, NoteTab(listOf(noteId))) },
                        active = at,
                    )
                }
                else ->
                    state.copy(
                        tabs =
                            state.tabs.mapIndexed { index, tab ->
                                if (index != state.active) {
                                    tab
                                } else {
                                    // Anything ahead is dropped, as it is
                                    // after going back and then somewhere new.
                                    val trail = tab.history.take(tab.index + 1) + noteId
                                    tab.copy(history = trail, index = trail.lastIndex, title = "")
                                }
                            },
                    )
            }
        }

        /** Steps back inside the tab being read. False when it has nowhere to go. */
        fun back(): Boolean {
            val tab = _state.value.current ?: return false
            if (!tab.canGoBack) return false
            update { state ->
                state.copy(
                    tabs =
                        state.tabs.mapIndexed { index, each ->
                            if (index == state.active) each.copy(index = each.index - 1, title = "") else each
                        },
                )
            }
            return true
        }

        fun select(index: Int) = update { if (index in it.tabs.indices) it.copy(active = index) else it }

        /** Closes a tab, and returns false when that was the last one. */
        fun close(index: Int): Boolean {
            var anyLeft = true
            update { state ->
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
        ) = update { state ->
            state.copy(tabs = state.tabs.map { if (it.noteId == noteId) it.copy(title = title) else it })
        }

        /** Everything closed, for leaving the reader entirely. */
        fun clear() {
            _state.value = TabsState()
        }

        private fun update(block: (TabsState) -> TabsState) {
            _state.value = block(_state.value)
        }
    }
