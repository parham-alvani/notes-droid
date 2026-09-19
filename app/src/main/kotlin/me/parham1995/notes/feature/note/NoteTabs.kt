package me.parham1995.notes.feature.note

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultRepository
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

    /**
     * Shows [noteId], in a new tab or in the one being looked at.
     *
     * Opening it where you are pushes onto that tab's trail, the way
     * following a link does. A note already showing in some tab is switched
     * to instead: two tabs of one note is never what was meant.
     */
    fun opening(
        noteId: Long,
        inNewTab: Boolean,
    ): TabsState {
        val showing = tabs.indexOfFirst { it.noteId == noteId }
        return when {
            showing >= 0 -> copy(active = showing)
            tabs.isEmpty() -> TabsState(listOf(NoteTab(listOf(noteId))), 0)
            inNewTab -> {
                // Beside the one it came from, the way a browser does it.
                val at = (active + 1).coerceAtMost(tabs.size)
                copy(tabs = tabs.toMutableList().apply { add(at, NoteTab(listOf(noteId))) }, active = at)
            }
            else ->
                copy(
                    tabs =
                        tabs.mapIndexed { index, tab ->
                            if (index != active) {
                                tab
                            } else {
                                // Anything ahead is dropped, as it is after
                                // going back and then somewhere new.
                                val trail = tab.history.take(tab.index + 1) + noteId
                                tab.copy(history = trail, index = trail.lastIndex, title = "")
                            }
                        },
                )
        }
    }

    /** One step back inside the tab being read, or unchanged where it cannot. */
    fun goingBack(): TabsState =
        if (current?.canGoBack != true) {
            this
        } else {
            copy(
                tabs =
                    tabs.mapIndexed { index, tab ->
                        if (index == active) tab.copy(index = tab.index - 1, title = "") else tab
                    },
            )
        }

    fun selecting(index: Int): TabsState = if (index in tabs.indices) copy(active = index) else this

    fun closing(index: Int): TabsState {
        if (index !in tabs.indices) return this
        val left = tabs.toMutableList().apply { removeAt(index) }
        return TabsState(
            tabs = left,
            // Stay where you were looking: closing a tab to the left should
            // not move the one being read out from under you.
            active = if (index < active) active - 1 else active.coerceAtMost(left.lastIndex).coerceAtLeast(0),
        )
    }

    fun retitling(
        noteId: Long,
        title: String,
    ): TabsState = copy(tabs = tabs.map { if (it.noteId == noteId) it.copy(title = title) else it })
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
    constructor(
        private val settings: SettingsStore,
        private val repository: VaultRepository,
    ) {
        private val _state = MutableStateFlow(TabsState())
        val state: StateFlow<TabsState> = _state.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Whether what was written down has been read yet.
         *
         * Nothing is written back before it has: saving an empty set over the
         * stored one is the obvious way to build something that forgets
         * everything exactly once per launch. It is also what tells the app
         * it is time to reopen where the reader left off.
         */
        private val _restored = MutableStateFlow(false)
        val restored: StateFlow<Boolean> = _restored.asStateFlow()

        init {
            scope.launch {
                val stored = TabsCodec.decode(settings.openTabs.first())
                if (stored != null) _state.value = resolve(stored)
                _restored.value = true
            }
        }

        /**
         * Turns written-down paths back into the notes they name.
         *
         * A path that no longer resolves is dropped rather than restored as a
         * tab that opens nothing: notes get renamed, and a reindex reissues
         * every id, so this is the normal case rather than the exception.
         */
        private suspend fun resolve(stored: StoredTabs): TabsState {
            val tabs =
                stored.tabs.mapNotNull { tab ->
                    val ids = tab.trail.mapNotNull { repository.resolve(it.vaultId, it.path) }
                    if (ids.isEmpty()) null else NoteTab(ids, tab.index.coerceIn(0, ids.lastIndex))
                }
            return if (tabs.isEmpty()) TabsState() else TabsState(tabs, stored.active.coerceIn(0, tabs.lastIndex))
        }

        private fun remember() {
            if (!_restored.value) return
            val snapshot = _state.value
            scope.launch {
                val tabs =
                    snapshot.tabs.mapNotNull { tab ->
                        val trail =
                            tab.history.mapNotNull { id ->
                                repository.locate(id)?.let { TabRef(it.first, it.second) }
                            }
                        if (trail.isEmpty()) null else StoredTab(trail, tab.index.coerceIn(0, trail.lastIndex))
                    }
                settings.setOpenTabs(
                    if (tabs.isEmpty()) {
                        ""
                    } else {
                        TabsCodec.encode(
                            StoredTabs(tabs, snapshot.active.coerceIn(0, tabs.lastIndex)),
                        )
                    },
                )
            }
        }

        fun open(
            noteId: Long,
            inNewTab: Boolean,
        ) = update { it.opening(noteId, inNewTab) }

        /** Steps back inside the tab being read. False when it has nowhere to go. */
        fun back(): Boolean {
            if (_state.value.current?.canGoBack != true) return false
            update { it.goingBack() }
            return true
        }

        fun select(index: Int) = update { it.selecting(index) }

        /** Closes a tab, and returns false when that was the last one. */
        fun close(index: Int): Boolean {
            update { it.closing(index) }
            return _state.value.tabs.isNotEmpty()
        }

        fun retitle(
            noteId: Long,
            title: String,
        ) = update { it.retitling(noteId, title) }

        /** Everything closed, for leaving the reader entirely. */
        fun clear() = update { TabsState() }

        private fun update(block: (TabsState) -> TabsState) {
            val next = block(_state.value)
            if (next == _state.value) return
            _state.value = next
            remember()
        }
    }
