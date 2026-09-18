package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.BacklinkRow
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.HeadingDao
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.data.database.LinkDao
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.data.database.TaskDao
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.data.database.VaultDao
import me.parham1995.notes.data.database.VaultEntity
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
)

/** A note prepared for display. */
data class RenderedNote(
    val id: Long,
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
 * Everything the UI is allowed to ask for. Keeping this the only surface means
 * the screens never touch a DAO, a file, or the parser directly.
 */
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
    ) {
        val noteCount: Flow<Int> = notes.count()

        /** The repositories making up this vault, for the browser's switcher. */
        fun vaults(): Flow<List<VaultEntity>> = vaults.observe()

        /** Every open task in the vault, soonest first, undated last. */
        fun openTasks(): Flow<List<TaskRow>> = tasks.open()

        suspend fun overdueCount(today: String): Int = tasks.overdueCount(today)

        suspend fun dueTodayCount(today: String): Int = tasks.dueTodayCount(today)

        fun recentlyOpened(limit: Int = RECENT_LIMIT): Flow<List<NoteEntity>> = notes.recentlyOpened(limit)

        /**
         * One level of the tree, re-emitted whenever the notes table changes.
         *
         * The browser used to run this once when the screen opened. Before the
         * first sync that is an empty vault, and nothing ever asked again --
         * so the tree stayed empty while search, which queries per keystroke,
         * worked perfectly.
         */
        fun childrenFlow(parent: String): Flow<List<VaultItem>> =
            combine(
                notes.allParentsFlow(),
                notes.childrenOfFlow(parent),
                blobs.attachmentPaths(),
            ) { parents, childNotes, attachments ->
                buildChildren(parent, parents, childNotes, attachments)
            }.flowOn(Dispatchers.Default)

        suspend fun children(parent: String): List<VaultItem> =
            withContext(Dispatchers.Default) {
                buildChildren(parent, notes.allParents(), notes.childrenOf(parent), blobs.attachmentPaths().first())
            }

        /**
         * Folders are derived from the notes' parents rather than stored, so
         * there is no second structure to keep in step with the manifest.
         */
        private suspend fun buildChildren(
            parent: String,
            parents: List<String>,
            childNotes: List<NoteEntity>,
            attachmentPaths: List<String>,
        ): List<VaultItem> {
            val prefix = if (parent.isEmpty()) "" else "$parent/"
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
                    val own = notes.byPath("$prefix$name/$name.md")
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
                    .map { VaultItem(it.path, it.name, isFolder = false, noteId = it.id, isRtl = it.isRtl) }

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
                val text = files.readText(entity.path) ?: return@withContext null

                val parsed =
                    MarkdownParser.parseNote(text) { target ->
                        // Embeds are written vault-relative or note-relative;
                        // both have to land on a real path.
                        resolveAttachment(target, entity.path)
                    }

                val refs = notes.allIds()
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
                    path = entity.path,
                    title = entity.title,
                    blocks = parsed.blocks,
                    isRtl = entity.isRtl,
                    isFolderNote = entity.isFolderNote,
                    headings = headings.byNote(entity.id),
                    linkTargets = targets,
                    brokenTargets = allTargets - targets.keys,
                )
            }

        /** Where an embed's file actually lives, vault-relative. */
        private fun resolveAttachment(
            target: String,
            source: String,
        ): String {
            if (!target.contains("..")) return target
            val base =
                source
                    .substringBeforeLast('/', "")
                    .split('/')
                    .filter { it.isNotEmpty() }
                    .toMutableList()
            target.split('/').forEach { segment ->
                when (segment) {
                    "." -> Unit
                    ".." -> if (base.isNotEmpty()) base.removeAt(base.lastIndex)
                    else -> base += segment
                }
            }
            return base.joinToString("/")
        }

        suspend fun markOpened(id: Long) = notes.markOpened(id, System.currentTimeMillis())

        suspend fun backlinks(id: Long): List<BacklinkRow> = links.backlinks(id)

        suspend fun backlinkCount(id: Long): Int = links.backlinkCount(id)

        /** A note exactly as it is written, for sharing it somewhere else. */
        suspend fun markdown(id: Long): String? = notes.byId(id)?.let { files.readText(it.path) }

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
            return search.mentions(note.title, linked)
        }

        /** Full-text results, ranked with the title weighted above the body. */
        suspend fun search(query: String): List<SearchHit> = search.search(query)

        /** Name-only matches, for jumping straight to a note while typing. */
        suspend fun quickSwitch(query: String): List<NoteEntity> =
            if (query.isBlank()) {
                emptyList()
            } else {
                notes.searchByName(
                    me.parham1995.notes.markdown.Slugs
                        .fold(query.trim()),
                    QUICK_LIMIT,
                )
            }

        private companion object {
            const val RECENT_LIMIT = 12
            const val QUICK_LIMIT = 15
        }
    }
