package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.BacklinkRow
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.FolderNoteRow
import me.parham1995.notes.data.database.HeadingDao
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.data.database.LinkDao
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.data.database.TaskDao
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.data.database.VaultEntity
import me.parham1995.notes.data.database.escapeLike
import me.parham1995.notes.markdown.LinkKind
import me.parham1995.notes.markdown.LinkResolver
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.MdBlock
import javax.inject.Inject
import javax.inject.Singleton

/** One row in the browser. */
data class VaultItem(
    val path: String,
    val name: String,
    val isFolder: Boolean,
    /** For a folder, the id of its `X/X.md` landing page when it has one. */
    val noteId: Long? = null,
    val isRtl: Boolean = false,
    /**
     * True for a file the reader hands to a viewer rather than renders -- a
     * PDF, an image, a recording.
     *
     * Without these the browser could only show markdown, which is fine for a
     * vault of notes and shows an empty tree for a repository of scanned
     * documents.
     */
    val isAttachment: Boolean = false,
    /** When it was last opened, and when it last changed -- for ordering. */
    val openedAt: Long? = null,
    val changedAt: Long = 0,
)

/** A note prepared for display. */
data class RenderedNote(
    val id: Long,
    val vaultId: Long,
    val path: String,
    val title: String,
    val blocks: List<MdBlock>,
    val isRtl: Boolean,
    /**
     * True when this note is a folder's landing page -- `X/X.md`. The reader
     * offers the folder's contents alongside it, because the note is only half
     * of what a folder is.
     */
    val isFolderNote: Boolean,
    val headings: List<HeadingEntity>,
    /** The block this note was last left at, so reopening it resumes. */
    val scrollIndex: Int,
    /** Raw wikilink target to note id, so a tap can navigate without re-resolving. */
    val linkTargets: Map<String, Long>,
    /**
     * Targets that resolve to nothing. Rendered as broken rather than dropped:
     * many are deliberate placeholders in index notes, and seeing them is how
     * the vault's own gaps stay visible.
     */
    val brokenTargets: Set<String>,
)

/**
 * One note and what it is connected to, one hop out.
 *
 * One hop, not the whole vault: 2,407 notes and 9,593 links drawn at once is a
 * screensaver. What answers a question is the handful around whatever is open.
 */
data class Neighbours(
    val centre: NoteEntity,
    /** Notes this one links to. */
    val outgoing: List<BacklinkRow>,
    /** Notes that link to it. */
    val incoming: List<BacklinkRow>,
) {
    /** Links that go both ways, which are the strongest connections here. */
    val mutual: Set<Long> get() = outgoing.map { it.noteId }.toSet() intersect incoming.map { it.noteId }.toSet()
}

/**
 * Everything the UI is allowed to ask for. Keeping this the only surface means
 * the screens never touch a DAO, a file, or the parser directly.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class VaultRepository
    @Inject
    constructor(
        private val notes: NoteDao,
        private val links: LinkDao,
        private val headings: HeadingDao,
        private val files: VaultFileStore,
        private val search: SearchIndex,
        private val tasks: TaskDao,
        private val blobs: BlobDao,
        private val vaults: VaultDao,
        private val settings: SettingsStore,
    ) {
        /**
         * The vault being read.
         *
         * Held here rather than passed into every call, because every screen
         * wants the same one and a parameter threaded through forty methods is
         * forty chances to pass the wrong vault. Falls back to the first, which
         * is what a single-vault install and a fresh one both want.
         */
        val activeVaultId: Flow<Long> =
            combine(settings.settings, vaults.observe()) { current, all ->
                all.firstOrNull { it.id == current.activeVaultId }?.id ?: all.firstOrNull()?.id ?: 0L
            }.distinctUntilChanged()

        private suspend fun active(): Long = activeVaultId.first()

        val noteCount: Flow<Int> = activeVaultId.flatMapLatest { notes.count(it) }

        /** Every vault, for the switcher. */
        fun vaults(): Flow<List<VaultEntity>> = vaults.observe()

        suspend fun setActiveVault(id: Long) = settings.setActiveVault(id)

        /** Every open task in the vault, soonest first, undated last. */
        fun openTasks(): Flow<List<TaskRow>> = activeVaultId.flatMapLatest { tasks.open(it) }

        /** The tasks written in one note, so one can be ticked where it sits. */
        suspend fun tasksIn(noteId: Long): List<TaskRow> = tasks.byNote(noteId)

        suspend fun overdueCount(today: String): Int = tasks.overdueCount(today)

        suspend fun dueTodayCount(today: String): Int = tasks.dueTodayCount(today)

        /** Open tasks in every vault, keyed by vault. */
        fun openTaskCounts(): Flow<Map<Long, Int>> =
            tasks.openCountsByVault().map { rows -> rows.associate { it.vaultId to it.count } }

        fun recentlyOpened(limit: Int = RECENT_LIMIT): Flow<List<NoteEntity>> =
            activeVaultId.flatMapLatest { notes.recentlyOpened(it, limit) }

        /**
         * One level of the tree, re-emitted whenever the notes table changes.
         *
         * The browser used to run this once when the screen opened. Before the
         * first sync that is an empty vault, and nothing ever asked again --
         * so the tree stayed empty while search, which queries per keystroke,
         * worked perfectly.
         *
         * Room re-runs every query here whenever its table changes at all,
         * and opening a note or scrolling one writes to `notes` -- so each
         * source is de-duplicated, and so is the level itself, rather than
         * redrawing the tree for a note opened in some other folder.
         */
        fun childrenFlow(parent: String): Flow<List<VaultItem>> =
            activeVaultId
                .flatMapLatest { vaultId ->
                    combine(
                        notes.allParentsFlow(vaultId).distinctUntilChanged(),
                        notes.childrenOfFlow(vaultId, parent).distinctUntilChanged(),
                        blobs.attachmentPaths(vaultId).distinctUntilChanged(),
                        notes.folderNotesFlow(vaultId).distinctUntilChanged(),
                    ) { parents, childNotes, attachments, folderNotes ->
                        buildChildren(parent, parents, childNotes, attachments, folderNotes)
                    }
                }.distinctUntilChanged()
                .flowOn(Dispatchers.Default)

        suspend fun children(parent: String): List<VaultItem> =
            withContext(Dispatchers.Default) {
                val vaultId = active()
                buildChildren(
                    parent = parent,
                    parents = notes.allParents(vaultId),
                    childNotes = notes.childrenOf(vaultId, parent),
                    attachmentPaths = blobs.attachmentPaths(vaultId).first(),
                    folderNotes = notes.folderNotes(vaultId),
                )
            }

        /**
         * Folders are derived from the notes' parents rather than stored, so
         * there is no second structure to keep in step with the manifest.
         */
        private fun buildChildren(
            parent: String,
            parents: List<String>,
            childNotes: List<NoteEntity>,
            attachmentPaths: List<String>,
            folderNotes: List<FolderNoteRow>,
        ): List<VaultItem> {
            val prefix = if (parent.isEmpty()) "" else "$parent/"
            val landingPages = folderNotes.associateBy { it.path }
            val under = attachmentPaths.filter { it.startsWith(prefix) }
            // Folders come from the attachments as well as the notes: a
            // repository of scanned documents has directories full of PDFs and
            // not one note to imply them.
            val folders =
                (parents.asSequence() + under.asSequence().map { it.substringBeforeLast('/', "") })
                    .filter { it.startsWith(prefix) && it != parent }
                    .map { it.removePrefix(prefix).substringBefore('/') }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .sortedBy { it.lowercase() }
                    .toList()

            val folderItems =
                folders.map { name ->
                    // A folder's own note is `Folder/Folder.md`; it opens when
                    // the label is tapped and is hidden from the list inside.
                    val own = landingPages["$prefix$name/$name.md"]
                    VaultItem(
                        path = "$prefix$name",
                        name = name,
                        isFolder = true,
                        noteId = own?.id,
                        isRtl = own?.isRtl ?: false,
                    )
                }

            val noteItems =
                childNotes
                    .filterNot { it.isFolderNote && it.name == parent.substringAfterLast('/') }
                    .map {
                        VaultItem(
                            path = it.path,
                            name = it.name,
                            isFolder = false,
                            noteId = it.id,
                            isRtl = it.isRtl,
                            openedAt = it.openedAt,
                            changedAt = it.indexedAt,
                        )
                    }

            val attachmentItems =
                under
                    .filter { !it.removePrefix(prefix).contains('/') }
                    .map { path ->
                        VaultItem(
                            path = path,
                            name = path.substringAfterLast('/'),
                            isFolder = false,
                            isAttachment = true,
                        )
                    }.sortedBy { it.name.lowercase() }

            return folderItems + noteItems + attachmentItems
        }

        suspend fun note(id: Long): RenderedNote? =
            withContext(Dispatchers.Default) {
                val entity = notes.byId(id) ?: return@withContext null
                val text = files.readText(entity.vaultId, entity.path) ?: return@withContext null
                // This vault's files only: an embed resolves inside the vault
                // that wrote it, like a link.
                val attachments = blobs.attachmentPaths(entity.vaultId).first().toHashSet()

                val parsed =
                    MarkdownParser.parseNote(text) { target ->
                        AttachmentResolver.resolve(target, entity.path, attachments)
                    }

                val refs = notes.allIds(entity.vaultId)
                val resolver = LinkResolver(refs.map { it.path })
                val byPath = refs.associate { it.path to it.id }
                val targets =
                    parsed.links
                        .mapNotNull { link ->
                            val path = resolver.resolve(link.rawTarget, entity.path) ?: return@mapNotNull null
                            val noteId = byPath[path] ?: return@mapNotNull null
                            link.rawTarget to noteId
                        }.toMap()

                val allTargets =
                    parsed.links
                        .filter { it.kind == LinkKind.WIKILINK }
                        .map { it.rawTarget }
                        .filter { it.isNotEmpty() }
                        .toSet()

                RenderedNote(
                    id = entity.id,
                    vaultId = entity.vaultId,
                    path = entity.path,
                    title = entity.title,
                    blocks = parsed.blocks,
                    isRtl = entity.isRtl,
                    isFolderNote = entity.isFolderNote,
                    headings = headings.byNote(entity.id),
                    scrollIndex = entity.scrollIndex,
                    linkTargets = targets,
                    brokenTargets = allTargets - targets.keys,
                )
            }

        suspend fun markOpened(id: Long) = notes.markOpened(id, System.currentTimeMillis())

        suspend fun backlinks(id: Long): List<BacklinkRow> = links.backlinks(id)

        suspend fun backlinkCount(id: Long): Int = links.backlinkCount(id)

        /** A note exactly as it is written, for sharing it somewhere else. */
        suspend fun markdown(id: Long): String? = notes.byId(id)?.let { files.readText(it.vaultId, it.path) }

        /** Records where a note was left, so opening it again resumes there. */
        suspend fun rememberScroll(
            id: Long,
            block: Int,
        ) = notes.rememberScroll(id, block)

        /** What one note is connected to, for the graph. */
        suspend fun neighbours(id: Long): Neighbours? {
            val centre = notes.byId(id) ?: return null
            return Neighbours(
                centre = centre,
                outgoing = links.outgoing(id),
                incoming = links.backlinks(id).distinctBy { it.noteId },
            )
        }

        /** One note at random, for a vault large enough to have forgotten some. */
        suspend fun randomNote(): NoteEntity? = notes.random(active())

        /**
         * Notes that say this one's name without linking to it.
         *
         * The note itself and everything that already links here are excluded,
         * so what is left is exactly the connections somebody wrote in prose
         * and never turned into a link. In a vault where links are typed by
         * hand, that is most of them.
         */
        suspend fun unlinkedMentions(id: Long): List<SearchHit> {
            val note = notes.byId(id) ?: return emptyList()
            val linked = links.backlinks(id).map { it.noteId }.toSet() + id
            return search.mentions(note.vaultId, note.title, linked)
        }

        /**
         * Where a note lives, and the note that lives there.
         *
         * The pair a reader needs to write a note down and find it again: ids
         * are row numbers that a reindex reissues, a vault and a path are not.
         */
        suspend fun locate(id: Long): Pair<Long, String>? = notes.byId(id)?.let { it.vaultId to it.path }

        /** The note at a path, as the id to open and the title to label it with. */
        suspend fun resolve(
            vaultId: Long,
            path: String,
        ): Pair<Long, String>? = notes.byPath(vaultId, path)?.let { it.id to it.title.ifBlank { it.name } }

        /** Full-text results, ranked with the title weighted above the body. */
        suspend fun search(query: String): List<SearchHit> = search.search(active(), query)

        /** Name-only matches, for jumping straight to a note while typing. */
        suspend fun quickSwitch(query: String): List<NoteEntity> =
            if (query.isBlank()) {
                emptyList()
            } else {
                notes.searchByName(
                    active(),
                    escapeLike(
                        me.parham1995.notes.markdown.Slugs
                            .fold(query.trim()),
                    ),
                    QUICK_LIMIT,
                )
            }

        private companion object {
            const val RECENT_LIMIT = 12
            const val QUICK_LIMIT = 15
        }
    }
