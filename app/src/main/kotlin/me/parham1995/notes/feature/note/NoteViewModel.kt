package me.parham1995.notes.feature.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.parham1995.notes.data.Destinations
import me.parham1995.notes.data.IconStore
import me.parham1995.notes.data.PeriodNeighbours
import me.parham1995.notes.data.ReadingSettings
import me.parham1995.notes.data.RenderedNote
import me.parham1995.notes.data.SearchHit
import me.parham1995.notes.data.SettingsStore
import me.parham1995.notes.data.VaultFileSource
import me.parham1995.notes.data.VaultRepository
import me.parham1995.notes.data.VaultWriteRepository
import me.parham1995.notes.data.WriteResult
import me.parham1995.notes.data.database.BacklinkRow
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.icons.IconSpec
import me.parham1995.notes.markdown.HeadingPath
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.NoteMatch
import me.parham1995.notes.markdown.NoteSearch
import me.parham1995.notes.markdown.Transclusion
import me.parham1995.notes.markdown.footnotes
import me.parham1995.notes.markdown.plainText
import me.parham1995.notes.ui.VaultRowItem
import me.parham1995.notes.ui.render.Transcluded
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
    /** Whether this note's vault can be written to, and an author is set. */
    val writable: Boolean = false,
    /** The last thing a write had to say, shown once and dismissed. */
    val message: String? = null,
    /** The journal either side, when this note is one of its vault's daily notes. */
    val periodic: PeriodNeighbours? = null,
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
        private val writes: VaultWriteRepository,
        private val destinations: Destinations,
    ) : ViewModel() {
        val tabs: StateFlow<TabsState> = openTabs.state

        fun openTab(
            noteId: Long,
            inNewTab: Boolean = true,
            fresh: Boolean = false,
        ) = openTabs.open(noteId, inNewTab, fresh)

        fun selectTab(index: Int) = openTabs.select(index)

        /** Steps back inside the tab being read. */
        fun back() = openTabs.back()

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

        fun dismissMessage() {
            _state.value = _state.value.copy(message = null)
        }

        /**
         * Ticks the task written on [line] of the note that is open.
         *
         * Matched by source line rather than by text, because a note can hold
         * the same one-line task under two headings -- "- [ ] follow up" is in
         * this vault several times over -- and ticking the wrong one is a
         * silent, believable mistake.
         */
        fun completeTask(line: Int) =
            viewModelScope.launch {
                val noteId = _state.value.note?.id ?: return@launch
                val row = repository.tasksIn(noteId).firstOrNull { it.line == line }
                if (row == null) {
                    _state.value = _state.value.copy(message = "that task is not in the index yet")
                    return@launch
                }
                val said =
                    when (val result = writes.completeTask(row)) {
                        WriteResult.Pushed -> "Saved"
                        is WriteResult.Queued -> "Saved here - it goes up with the next sync"
                        WriteResult.Unchanged -> "Already done"
                        is WriteResult.Refused -> result.why
                    }
                _state.value = _state.value.copy(message = said)
                // The file on disk has changed, so what is on screen is one
                // edit out of date. `load` refuses to reload the note it is
                // already showing, which is what makes this necessary rather
                // than tidy.
                if (said == "Saved" || said.startsWith("Saved here")) reload()
            }

        private suspend fun reload() {
            val id = _state.value.note?.id ?: return
            val note = repository.note(id) ?: return
            _state.value = _state.value.copy(note = note)
        }

        fun load(id: Long) {
            if (_state.value.note?.id == id) return
            // The journal strip is kept while the next note loads, so stepping
            // along a journal does not pull the page up and down under the finger.
            _state.value = NoteUiState(loading = true, periodic = _state.value.periodic)
            viewModelScope.launch {
                val note = repository.note(id)
                if (note == null) {
                    _state.value = NoteUiState(loading = false, missing = true)
                    return@launch
                }
                repository.markOpened(id)
                val assignments = icons.config.first()
                val writable = writes.canWrite(note.vaultId).first()
                _state.value =
                    NoteUiState(
                        loading = false,
                        note = note,
                        backlinks = repository.backlinks(id),
                        icon = assignments.forFile(note.vaultId, note.path),
                        writable = writable,
                        periodic = destinations.neighbours(note.vaultId, note.path),
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

        /**
         * What an `![[embed]]` of another note shows: that note, or the section
         * of it the heading names, with the links it holds resolved from where
         * it was written.
         *
         * Null when the embed names no note, or a heading that is not there.
         * Front matter is left out; it is the other note's metadata, and it is
         * not drawn in that note either.
         */
        suspend fun transclusion(
            target: String,
            heading: String?,
        ): Transcluded? {
            val id = targetOf(target) ?: return null
            val note = repository.note(id) ?: return null
            val section = Transclusion.section(note.blocks, heading, note.blockTargets) ?: return null
            return Transcluded(
                noteId = id,
                title = note.title,
                blocks = section.filterNot { it is MdBlock.FrontMatter },
                linkTargets = note.linkTargets,
                brokenLinks = note.brokenTargets,
                footnotes = note.blocks.footnotes(),
            )
        }

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
 * agrees with nothing. `Chapter#Section` is the Section under that Chapter,
 * and `^id` is the block carrying that id, from [blockRefs].
 *
 * Null when nothing matches, which is a link to a heading that was renamed.
 * The note still opens; it just opens where it was left.
 */
internal fun blockForHeading(
    headings: List<HeadingEntity>,
    heading: String?,
    blockRefs: Map<String, Int> = emptyMap(),
): Int? {
    val wanted = heading?.trim().orEmpty()
    if (wanted.isEmpty()) return null
    if (wanted.startsWith('^')) return blockRefs[wanted.substring(1)]
    val ordered = headings.sortedBy { it.ordinal }
    return HeadingPath.find(ordered.map { it.level to it.text }, wanted)?.let { ordered[it].blockIndex }
}
