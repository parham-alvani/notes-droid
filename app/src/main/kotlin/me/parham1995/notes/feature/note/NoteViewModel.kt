package me.parham1995.notes.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.parham1995.notes.data.IconStore
import me.parham1995.notes.data.RenderedNote
import me.parham1995.notes.data.SearchHit
import me.parham1995.notes.data.VaultFileSource
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.BacklinkRow
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.markdown.NoteMatch
import me.parham1995.notes.markdown.NoteSearch
import me.parham1995.notes.ui.VaultRowItem
import java.io.File
import javax.inject.Inject

data class NoteUiState(
    val loading: Boolean = true,
    val note: RenderedNote? = null,
    val backlinks: List<BacklinkRow> = emptyList(),
    /** Notes that name this one without linking to it. Loaded on demand. */
    val mentions: List<SearchHit> = emptyList(),
    val mentionsLoaded: Boolean = false,
    /** What is being looked for inside this note, and where it is. */
    val findQuery: String = "",
    val matches: List<NoteMatch> = emptyList(),
    val icon: IconSpec? = null,
    /**
     * What the folder holds, when this note is a folder's landing page. Empty
     * for an ordinary note, which is what hides the tabs.
     */
    val contents: List<VaultRowItem> = emptyList(),
    val missing: Boolean = false,
) {
    val isFolderNote: Boolean get() = note?.isFolderNote == true

    /** Targets with no destination, so the renderer can style them as broken. */
    val brokenLinks: Set<String> get() = note?.brokenTargets.orEmpty()
}

@HiltViewModel
class NoteViewModel
    @Inject
    constructor(
        private val repository: VaultRepository,
        private val icons: IconStore,
        private val files: VaultFileSource,
    ) : ViewModel() {
        private val _state = MutableStateFlow(NoteUiState())
        val state: StateFlow<NoteUiState> = _state.asStateFlow()

        fun load(id: Long) {
            if (_state.value.note?.id == id) return
            _state.value = NoteUiState(loading = true)
            viewModelScope.launch {
                val note = repository.note(id)
                if (note == null) {
                    _state.value = NoteUiState(loading = false, missing = true)
                    return@launch
                }
                repository.markOpened(id)
                val assignments = icons.config.first()
                _state.value =
                    NoteUiState(
                        loading = false,
                        note = note,
                        backlinks = repository.backlinks(id),
                        icon = assignments.forFile(note.vaultId, note.path),
                        contents =
                            if (!note.isFolderNote) {
                                emptyList()
                            } else {
                                // `A/B/B.md` is the landing page for `A/B`.
                                // `children` already leaves the note itself
                                // out of its own folder's listing.
                                repository
                                    .children(note.path.substringBeforeLast('/', ""))
                                    .map { VaultRowItem(it, assignments.forPath(note.vaultId, it.path, it.isFolder)) }
                            },
                    )
            }
        }

        /**
         * Finds [query] inside the note that is open.
         *
         * Run on every keystroke against blocks already in memory, which for
         * the largest note here is a scan of 89KB of text -- fast enough that
         * debouncing it would only add latency.
         */
        fun find(query: String) {
            val blocks =
                _state.value.note
                    ?.blocks
                    .orEmpty()
            _state.value =
                _state.value.copy(
                    findQuery = query,
                    matches = if (query.isBlank()) emptyList() else NoteSearch.find(blocks, query),
                )
        }

        fun clearFind() {
            _state.value = _state.value.copy(findQuery = "", matches = emptyList())
        }

        /**
         * Records where the note was left.
         *
         * Called as the screen goes away rather than on every scroll: this is a
         * write per note read, not one per frame.
         */
        fun rememberScroll(block: Int) {
            val id = _state.value.note?.id ?: return
            viewModelScope.launch { repository.rememberScroll(id, block) }
        }

        /** The note's own markdown, for handing to another app. */
        suspend fun markdown(): String? =
            _state.value.note
                ?.id
                ?.let { repository.markdown(it) }

        /**
         * Looked up only when the sheet is opened.
         *
         * It is a full-text search over the whole vault, which is cheap but not
         * free, and most notes are opened and read without anyone ever asking
         * what else mentions them.
         */
        fun loadMentions() {
            if (_state.value.mentionsLoaded) return
            val id = _state.value.note?.id ?: return
            viewModelScope.launch {
                val found = repository.unlinkedMentions(id)
                _state.value = _state.value.copy(mentions = found, mentionsLoaded = true)
            }
        }

        /**
         * The attachment on disk, downloading it first if this install has only
         * ever recorded it.
         *
         * Null means it could not be had at all -- no network, no token, or a
         * path the manifest has never heard of.
         */
        suspend fun attachment(path: String): File? = _state.value.note?.let { files.localFile(it.vaultId, path) }

        /** The note a wikilink points at, or null when it is broken. */
        fun targetOf(target: String): Long? =
            _state.value.note
                ?.linkTargets
                ?.get(target)
    }
