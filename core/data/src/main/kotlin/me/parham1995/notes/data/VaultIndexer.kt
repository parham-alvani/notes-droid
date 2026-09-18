package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.HeadingDao
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.data.database.IndexDao
import me.parham1995.notes.data.database.LinkDao
import me.parham1995.notes.data.database.LinkEntity
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.data.database.NoteWrite
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.markdown.LinkKind
import me.parham1995.notes.markdown.LinkResolver
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.ParsedNote
import me.parham1995.notes.markdown.Slugs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the searchable index over the synced vault.
 *
 * Two passes, and the split is necessary rather than tidy: a link can point at
 * a note that has not been parsed yet, so resolving during the first pass would
 * record a false broken link for every forward reference in the vault.
 *
 * Parsing runs in parallel and writes are batched into transactions. The batch
 * size is the whole performance story -- one transaction per note turns a
 * seconds-long index into a minutes-long one, because each commit is an fsync.
 */
@Singleton
class VaultIndexer
    @Inject
    constructor(
        private val database: NotesDatabase,
        private val files: VaultFileStore,
        private val notes: NoteDao,
        private val links: LinkDao,
        private val headings: HeadingDao,
        private val index: IndexDao,
        private val search: SearchIndex,
    ) {
        data class Progress(
            val done: Int,
            val total: Int,
        )

        /** Reindexes every markdown file currently on disk. */
        suspend fun indexAll(
            paths: List<PathAndSha>,
            onProgress: (Progress) -> Unit = {},
        ) {
            notes.clear()
            links.clear()
            headings.clear()
            search.clear()

            var done = 0
            paths.chunked(BATCH).forEach { batch ->
                val parsed = parseBatch(batch)
                writeBatch(parsed)
                done += batch.size
                onProgress(Progress(done, paths.size))
            }

            resolveLinks()
            search.optimize()
        }

        /** Reindexes only what changed, and drops what went away. */
        suspend fun indexChanged(
            changed: List<PathAndSha>,
            removed: List<String>,
        ) {
            removed.forEach { path ->
                notes.byPath(path)?.let { note ->
                    links.deleteBySource(note.id)
                    headings.deleteByNote(note.id)
                    search.delete(note.id)
                }
                notes.deleteByPath(path)
            }

            changed.chunked(BATCH).forEach { batch -> writeBatch(parseBatch(batch)) }

            // Cheaper than a full resolve and still correct: only links whose
            // target may have appeared or vanished need revisiting, and the
            // unresolved set is exactly those.
            resolveLinks()
        }

        private data class Indexed(
            val path: String,
            val sha: String,
            val size: Long,
            val note: ParsedNote,
        )

        private suspend fun parseBatch(batch: List<PathAndSha>): List<Indexed> =
            coroutineScope {
                batch
                    .map { entry ->
                        async(Dispatchers.Default) {
                            val text = files.readText(entry.path) ?: return@async null
                            val parsed = MarkdownParser.parseNote(text)
                            Indexed(entry.path, entry.sha, text.length.toLong(), parsed)
                        }
                    }.awaitAll()
                    .filterNotNull()
            }

        private suspend fun writeBatch(batch: List<Indexed>) {
            if (batch.isEmpty()) return

            val writes =
                batch.map { indexed ->
                    val name = indexed.path.substringAfterLast('/').removeSuffix(MD)
                    val parent = indexed.path.substringBeforeLast('/', "")
                    NoteWrite(
                        note =
                            NoteEntity(
                                path = indexed.path,
                                parent = parent,
                                name = name,
                                slug = Slugs.fold(name),
                                // In Obsidian a note's title is its file
                                // name. The first heading is part of the
                                // body and is rendered as one.
                                title = name,
                                blobSha = indexed.sha,
                                size = indexed.size,
                                // `X/X.md` is the folder's landing page.
                                isFolderNote = parent.substringAfterLast('/') == name,
                                isRtl = indexed.note.isRtl,
                                hasMermaid = indexed.note.hasMermaid,
                                hasMath = indexed.note.hasMath,
                                indexedAt = System.currentTimeMillis(),
                            ),
                        headings =
                            indexed.note.headings.mapIndexed { ordinal, heading ->
                                HeadingEntity(
                                    noteId = 0,
                                    level = heading.level,
                                    text = heading.text,
                                    slug = heading.slug,
                                    ordinal = ordinal,
                                    blockIndex = heading.blockIndex,
                                )
                            },
                        links =
                            indexed.note.links
                                .filter { it.kind == LinkKind.WIKILINK || it.kind == LinkKind.WIKI_EMBED }
                                .mapIndexed { ordinal, link ->
                                    LinkEntity(
                                        srcId = 0,
                                        kind = link.kind.name,
                                        rawTarget = link.rawTarget,
                                        alias = link.alias,
                                        heading = link.heading,
                                        targetId = null,
                                        context = link.context,
                                        ordinal = ordinal,
                                    )
                                },
                    )
                }

            val ids = index.writeBatch(writes)

            // Outside the transaction: the FTS table is not one of Room's, and
            // taking its writer connection from inside would deadlock.
            ids.forEachIndexed { position, id ->
                val indexed = batch[position]
                val name = indexed.path.substringAfterLast('/').removeSuffix(MD)
                search.upsert(id, name, indexed.note.plainText)
            }
        }

        /**
         * Second pass. Builds the resolver once over every known path, then
         * attaches each link to the note it names.
         */
        private suspend fun resolveLinks() =
            withContext(Dispatchers.Default) {
                val refs = notes.allIds()
                val byPath = refs.associate { it.path to it.id }
                val resolver = LinkResolver(byPath.keys)
                val sources = refs.associate { it.id to it.path }

                val targets =
                    links.unresolved().mapNotNull { link ->
                        val source = sources[link.srcId] ?: return@mapNotNull null
                        val path = resolver.resolve(link.rawTarget, source)
                        // Deliberately left null when nothing matches: a broken
                        // link is shown as broken rather than silently dropped.
                        link.id to path?.let(byPath::get)
                    }
                targets.chunked(BATCH).forEach { index.applyTargets(it) }
            }

        private companion object {
            /**
             * Large enough that commits are rare, small enough that a failure
             * does not lose much work.
             */
            const val BATCH = 200
            const val MD = ".md"
        }
    }

data class PathAndSha(
    val path: String,
    val sha: String,
)
