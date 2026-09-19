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
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.data.RenderedNote
import me.parham1995.notes.data.SearchHit
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultFileSource
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.database.BacklinkRow
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.NoteMatch
import me.parham1995.notes.markdown.NoteSearch
import me.parham1995.notes.markdown.plainText
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
        private val settings: SettingsStore,
        private val openTabs: NoteTabs,
    ) : ViewModel() {
        val tabs: StateFlow<TabsState> = openTabs.state

        fun openTab(
            noteId: Long,
            inNewTab: Boolean = true,
        ) = openTabs.open(noteId, inNewTab)

        fun selectTab(index: Int) = openTabs.select(index)

        fun retitleTab(
            noteId: Long,
            title: String,
        ) = openTabs.retitle(noteId, title)

        /** Closes the tab being read; false when it was the last one. */
        fun closeTab(index: Int): Boolean = openTabs.close(index)

        /**
         * What a link points at, before following it.
         *
         * Enough to decide with: the title, where it lives, and the note's
         * opening words. Following a link to find out it was not the one you
         * meant costs a load and the scroll position you were at.
         */
        suspend fun peek(noteId: Long): LinkTarget? {
            val note = repository.note(noteId) ?: return null
            val opening = openingLine(note.blocks)
            return LinkTarget(
                noteId = noteId,
                title = note.title,
                path = note.path,
                excerpt = opening.take(PEEK_CHARS),
            )
        }

        /**
         * Resizes the text by pinching the note.
         *
         * Reading size lived four taps away in Settings, which is three too
         * many for the thing the app exists to do. Snapped to the same steps
         * the setting offers rather than being continuous, so pinching and
         * then opening Settings does not show a size that is not on the list.
         */
        fun pinchTextScale(factor: Float) {
            viewModelScope.launch {
                val current = settings.current().reading.textScale
                val wanted = current * factor
                val nearest = ReadingSettings.TEXT_SCALES.minBy { kotlin.math.abs(it - wanted) }
                if (nearest != current) settings.setTextScale(nearest)
            }
        }

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

/** A link's destination, shown before it is followed. */
data class LinkTarget(
    val noteId: Long,
    val title: String,
    val path: String,
    val excerpt: String,
    /** The heading the link points at, when it points at one. */
    val heading: String? = null,
)

/** Enough of the opening to recognise a note by. */
private const val PEEK_CHARS = 240

/**
 * The first line of a note that says what it is about.
 *
 * Not simply the first paragraph. Notes in this vault routinely open with a
 * list of sources and a "Related notes:" line, so the first paragraph is
 * frequently that label -- which is what the preview showed, and it describes
 * every note equally.
 *
 * A label is short and ends in a colon; prose does neither. Falling back to
 * the first paragraph is deliberate: a preview of something is better than a
 * preview of nothing.
 */
internal fun openingLine(blocks: List<MdBlock>): String {
    val paragraphs =
        blocks
            .filterIsInstance<MdBlock.Paragraph>()
            .map { plainText(it.inlines).trim() }
            .filter { it.isNotEmpty() }
    val prose = paragraphs.firstOrNull { it.length >= PROSE_CHARS && !it.endsWith(':') }
    return (prose ?: paragraphs.firstOrNull()).orEmpty().take(PEEK_CHARS)
}

/** Below this, and ending in a colon, it is a heading for something else. */
private const val PROSE_CHARS = 40

/**
 * Which block a `[[Note#Heading]]` link means.
 *
 * Matched on the heading's own text, trimmed and ignoring case, because
 * Obsidian does not slugify an anchor -- the real ones in this vault contain
 * em dashes, parentheses and Persian, and slugifying either of those two ways
 * agrees with nothing.
 *
 * Null when nothing matches, which is a link to a heading that was renamed.
 * The note still opens; it just opens where it was left.
 */
internal fun blockForHeading(
    headings: List<HeadingEntity>,
    heading: String?,
): Int? {
    val wanted = heading?.trim().orEmpty()
    if (wanted.isEmpty()) return null
    return headings.firstOrNull { it.text.trim().equals(wanted, ignoreCase = true) }?.blockIndex
}
