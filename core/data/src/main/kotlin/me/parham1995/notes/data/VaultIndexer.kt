package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.AliasDao
import me.parham1995.notes.data.database.AliasEntity
import me.parham1995.notes.data.database.HeadingDao
import me.parham1995.notes.data.database.HeadingEntity
import me.parham1995.notes.data.database.IndexDao
import me.parham1995.notes.data.database.LinkDao
import me.parham1995.notes.data.database.LinkEntity
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.data.database.NoteWrite
import me.parham1995.notes.data.database.TagEntity
import me.parham1995.notes.data.database.TaskDao
import me.parham1995.notes.data.database.TaskEntity
import me.parham1995.notes.data.database.byPath
import me.parham1995.notes.markdown.LinkKind
import me.parham1995.notes.markdown.LinkResolver
import me.parham1995.notes.markdown.MarkdownParser
import me.parham1995.notes.markdown.ParsedNote
import me.parham1995.notes.markdown.Slugs
import me.parham1995.notes.markdown.TaskExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the searchable index over the synced vault.
 *
 * "Index" is broader than search: headings become the outline, links become
 * backlinks, and tasks become the list of what is open across the whole vault.
 * All of it is derived from the same parse, so it all happens in one pass.
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
        private val files: VaultFileStore,
        private val notes: NoteDao,
        private val links: LinkDao,
        private val headings: HeadingDao,
        private val tasks: TaskDao,
        private val index: IndexDao,
        private val search: SearchIndex,
        private val aliases: AliasDao,
    ) {
        data class Progress(
            val done: Int,
            val total: Int,
        )

        /**
         * Reindexes every markdown file currently on disk, bar the guides.
         *
         * A diff against what is indexed, not a wipe and a rebuild. Wiping
         * first cleared the notes and then asked the search index to drop
         * "this vault's notes" -- by which point there were none, so every
         * full reindex left a whole second copy of the vault in `note_fts`,
         * and the tasks, links and headings of the old rows behind with it.
         * It also handed every note a new id and forgot which had been opened
         * and where each was left. Updating in place keeps all of that, and
         * only what is no longer on disk is removed.
         */
        suspend fun indexAll(
            vaultId: Long,
            paths: List<PathAndSha>,
            onProgress: (Progress) -> Unit = {},
        ) {
            @Suppress("NAME_SHADOWING")
            val paths = paths.filterNot { isGuide(it.path) }
            val present = paths.mapTo(HashSet()) { it.path }
            remove(vaultId, notes.allIds(vaultId).map { it.path }.filterNot { it in present })

            var done = 0
            paths.chunked(BATCH).forEach { batch ->
                val parsed = parseBatch(vaultId, batch)
                writeBatch(vaultId, parsed)
                done += batch.size
                onProgress(Progress(done, paths.size))
            }

            resolveLinks(vaultId)
            search.optimize()
        }

        /**
         * Reindexes only what changed, and drops what went away.
         *
         * [ownWrite] says this device wrote [changed] itself, so a note the
         * reader was up to date with stays read -- see [IndexDao.writeBatch].
         */
        suspend fun indexChanged(
            vaultId: Long,
            changed: List<PathAndSha>,
            removed: List<String>,
            ownWrite: Boolean = false,
        ) {
            // A guide that arrives or changes is treated as one that went
            // away, so a file that used to be indexed stops being a note
            // rather than lingering as a row nothing will ever revisit.
            @Suppress("NAME_SHADOWING")
            val removed = removed + changed.map { it.path }.filter { isGuide(it) }

            @Suppress("NAME_SHADOWING")
            val changed = changed.filterNot { isGuide(it.path) }

            remove(vaultId, removed)

            changed.chunked(BATCH).forEach { batch -> writeBatch(vaultId, parseBatch(vaultId, batch), ownWrite) }

            // Cheaper than a full resolve and still correct: only links whose
            // target may have appeared or vanished need revisiting, and the
            // unresolved set is exactly those.
            resolveLinks(vaultId)
        }

        /** Drops notes with everything derived from them, search included. */
        private suspend fun remove(
            vaultId: Long,
            paths: List<String>,
        ) {
            paths.chunked(BATCH).forEach { batch ->
                // Outside the transaction, for the same reason as the writes:
                // the FTS table is not one of Room's.
                search.deleteAll(index.removeNotes(vaultId, batch))
            }
        }

        private data class Indexed(
            val path: String,
            val sha: String,
            val size: Long,
            val note: ParsedNote,
        )

        private suspend fun parseBatch(
            vaultId: Long,
            batch: List<PathAndSha>,
        ): List<Indexed> =
            coroutineScope {
                batch
                    .map { entry ->
                        async(Dispatchers.Default) {
                            val text = files.readText(vaultId, entry.path) ?: return@async null
                            val parsed = MarkdownParser.parseNote(text)
                            Indexed(entry.path, entry.sha, text.length.toLong(), parsed)
                        }
                    }.awaitAll()
                    .filterNotNull()
            }

        private suspend fun writeBatch(
            vaultId: Long,
            batch: List<Indexed>,
            ownWrite: Boolean = false,
        ) {
            if (batch.isEmpty()) return
            val now = System.currentTimeMillis()

            val writes =
                batch.map { indexed ->
                    val name = indexed.path.substringAfterLast('/').removeSuffix(MD)
                    val parent = indexed.path.substringBeforeLast('/', "")
                    NoteWrite(
                        note =
                            NoteEntity(
                                vaultId = vaultId,
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
                                indexedAt = now,
                                // Kept from the row when the sha has not
                                // moved; see IndexDao.writeBatch.
                                changedAt = now,
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
                        tasks =
                            TaskExtractor.extract(indexed.note).map { task ->
                                TaskEntity(
                                    noteId = 0,
                                    text = task.text,
                                    state = task.state.name,
                                    section = task.section,
                                    blockIndex = task.blockIndex,
                                    line = task.line,
                                    ordinal = task.ordinal,
                                    open = task.isOpen,
                                    actionableOn = task.actionableOn,
                                    scheduled = task.scheduled,
                                    due = task.due,
                                    done = task.done,
                                    recurring = task.recurring,
                                )
                            },
                        tags =
                            indexed.note.tags.map { tag ->
                                TagEntity(noteId = 0, name = tag, folded = Slugs.fold(tag))
                            },
                        aliases =
                            indexed.note.aliases.map { alias ->
                                AliasEntity(noteId = 0, alias = alias, folded = Slugs.fold(alias))
                            },
                        ownWrite = ownWrite,
                    )
                }

            val ids = index.writeBatch(writes)

            // Outside the transaction: the FTS table is not one of Room's, and
            // taking its writer connection from inside would deadlock. Still
            // one transaction of its own, rather than a commit per note.
            search.upsertAll(
                ids.mapIndexed { position, id ->
                    val indexed = batch[position]
                    val name = indexed.path.substringAfterLast('/').removeSuffix(MD)
                    SearchDocument(id, name, indexed.note.plainText, indexed.path, indexed.note.aliases)
                },
            )
        }

        /**
         * Second pass. Builds the resolver once over every known path, then
         * attaches each link to the note it names.
         */
        private suspend fun resolveLinks(vaultId: Long) =
            withContext(Dispatchers.Default) {
                // Built from one vault's paths, so a link can only ever resolve
                // inside the vault that wrote it -- which is the whole point of
                // them being separate.
                val refs = notes.allIds(vaultId)
                val byPath = refs.associate { it.path to it.id }
                // Aliases from the same vault, and consulted only after every
                // real name has missed -- the resolver's order, not this one's.
                val resolver = LinkResolver(byPath.keys, aliases.inVault(vaultId).byPath())
                val sources = refs.associate { it.id to it.path }

                // Only what found a target is written. Every candidate here is
                // already null, and one that still matches nothing is left as
                // it is -- shown as broken rather than dropped. Writing the
                // nulls back rewrote every broken link in the vault on every
                // pass, and a pass follows each ticked task.
                val targets =
                    links.unresolved(vaultId).mapNotNull { link ->
                        val source = sources[link.srcId] ?: return@mapNotNull null
                        val target = resolver.resolve(link.rawTarget, source)?.let(byPath::get)
                        target?.let { link.id to it }
                    }
                targets.chunked(BATCH).forEach { index.applyTargets(it) }
            }

        companion object {
            /**
             * Bumped whenever the indexer starts deriving something it did not
             * derive before.
             *
             * A sync only reparses files that changed, so a new kind of row --
             * tasks, here -- stays missing on an existing install forever: the
             * notes did not change, the indexer did. Recording the version
             * alongside the manifest lets one sync notice and rebuild.
             *
             * 1: tasks.
             * 2: vaults are separate, so every note records which it is in.
             * 3: the guides are synced but no longer indexed as notes.
             * 4: a heading records where it is, not what its id is.
             * 5: tasks record the source line they were written on.
             * 6: paragraphs split around embeds and display maths, comments
             *    dropped and any one-character status a task -- so the
             *    stored block positions and tasks moved.
             * 7: tags and aliases, and links that find a note by its alias.
             * 8: footnote definitions are gathered into one block at the end
             *    rather than left as paragraphs where they were written, so
             *    block positions after one moved.
             * 9: a `^block-id` on a line of its own is an address for the
             *    block above it, not a paragraph -- positions moved again.
             * 10: search holds text with Arabic and Persian letter forms,
             *    digits and half-spaces folded, plus each note's path, name
             *    and aliases. The index carried over from 9 still answers as
             *    it did; this rebuilds it so it answers the new way.
             */
            const val VERSION = 10

            /**
             * Files that are kept on the device but are not notes.
             *
             * A `CLAUDE.md` is instructions for the tooling that writes the
             * vault, not something anyone reads on a phone -- and there is one
             * in nearly every folder worth browsing, so they sit at every level
             * of the tree and answer to any search for a word about
             * conventions. Nothing links to them.
             *
             * They are still synced, and still on disk. This is only about
             * what is a note: skipping them here is what keeps them out of the
             * tree, the search, the tasks and the graph at once, rather than a
             * filter that each of those has to remember to apply.
             */
            val GUIDES = setOf("CLAUDE.md")

            fun isGuide(path: String): Boolean = path.substringAfterLast('/') in GUIDES

            /**
             * Large enough that commits are rare, small enough that a failure
             * does not lose much work.
             */
            private const val BATCH = 200
            private const val MD = ".md"
        }
    }

data class PathAndSha(
    val path: String,
    val sha: String,
)
