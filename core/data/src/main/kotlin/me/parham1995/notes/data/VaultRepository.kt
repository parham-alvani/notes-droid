package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.BacklinkRow
import me.parham1995.notes.data.database.HeadingDao
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.data.database.LinkDao
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.markdown.LinkKind
import me.parham1995.notes.markdown.LinkResolver
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.MdBlock
import javax.inject.Inject
import javax.inject.Singleton

/** One row in the browser: a folder, or a note. */
data class VaultItem(
    val path: String,
    val name: String,
    val isFolder: Boolean,
    /** For a folder, the id of its `X/X.md` landing page when it has one. */
    val noteId: Long? = null,
    val isRtl: Boolean = false,
)

/** A note prepared for display. */
data class RenderedNote(
    val id: Long,
    val path: String,
    val title: String,
    val blocks: List<MdBlock>,
    val isRtl: Boolean,
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
    ) {
        val noteCount: Flow<Int> = notes.count()

        fun recentlyOpened(limit: Int = RECENT_LIMIT): Flow<List<NoteEntity>> = notes.recentlyOpened(limit)

        /**
         * One level of the tree. Folders are derived from the notes' parents
         * rather than stored, so there is no second structure to keep in sync
         * with the manifest.
         */
        suspend fun children(parent: String): List<VaultItem> =
            withContext(Dispatchers.Default) {
                val prefix = if (parent.isEmpty()) "" else "$parent/"
                val folders =
                    notes
                        .allParents()
                        .asSequence()
                        .filter { it.startsWith(prefix) && it != parent }
                        .map { it.removePrefix(prefix).substringBefore('/') }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sortedBy { it.lowercase() }
                        .toList()

                val childNotes = notes.childrenOf(parent)

                val folderItems =
                    folders.map { name ->
                        // A folder's own note is `Folder/Folder.md`; it opens
                        // when the label is tapped and is hidden from the list.
                        val own = notes.byPath("$prefix$name/$name.md")
                        VaultItem(
                            path = "$prefix$name",
                            name = name,
                            isFolder = true,
                            noteId = own?.id,
                            isRtl = own?.isRtl ?: false,
                        )
                    }

                // A folder's own note is reached by tapping the folder, so it
                // is not listed again inside it.
                val noteItems =
                    childNotes
                        .filterNot { it.isFolderNote && it.name == parent.substringAfterLast('/') }
                        .map { VaultItem(it.path, it.name, isFolder = false, noteId = it.id, isRtl = it.isRtl) }

                folderItems + noteItems
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
