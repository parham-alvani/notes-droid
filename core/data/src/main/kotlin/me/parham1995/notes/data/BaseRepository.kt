package me.parham1995.notes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.data.database.AliasDao
import me.parham1995.notes.data.database.BlobDao
import me.parham1995.notes.data.database.LinkDao
import me.parham1995.notes.data.database.NoteDao
import me.parham1995.notes.data.database.TagDao
import me.parham1995.notes.data.database.byPath
import me.parham1995.notes.markdown.Base
import me.parham1995.notes.markdown.BaseEngine
import me.parham1995.notes.markdown.BaseIndex
import me.parham1995.notes.markdown.BaseNote
import me.parham1995.notes.markdown.BaseResult
import me.parham1995.notes.markdown.BaseStat
import me.parham1995.notes.markdown.FrontMatter
import me.parham1995.notes.markdown.LinkKind
import me.parham1995.notes.markdown.LinkResolver
import me.parham1995.notes.obsidian.PropertyTypes
import me.parham1995.notes.sync.BlobKind
import me.parham1995.notes.sync.VaultFilter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A base run for the page: its tables, its problems, and the file it came from. */
data class BaseAnswer(
    val result: BaseResult,
    /** The `.base` file, or null for a ` ```base ` block written in a note. */
    val path: String? = null,
    /** Each note path a cell links to, and the id that opens it. */
    val noteIds: Map<String, Long> = emptyMap(),
)

/**
 * Runs Obsidian bases over one vault's index.
 *
 * The index has what a base asks of most files -- paths, tags, links, when a
 * note changed -- and front matter is read from the file the first time a
 * base asks for a property of it, so a base filtered by folder reads only
 * the notes in that folder.
 */
@Singleton
class BaseRepository
    @Inject
    constructor(
        private val notes: NoteDao,
        private val links: LinkDao,
        private val tags: TagDao,
        private val aliases: AliasDao,
        private val blobs: BlobDao,
        private val files: VaultFileStore,
        private val log: SyncLog,
    ) {
        /** The `.base` file [target] names, seen from the note at [from]; null when there is none. */
        suspend fun resolveFile(
            vaultId: Long,
            target: String,
            from: String,
        ): String? {
            val bases = blobs.files(vaultId).map { it.path }.filter { Base.isBase(it) }
            return LinkResolver(bases).resolve(target.trim(), from)
        }

        /** Runs the `.base` file [target] names, as embedded in the note at [from]. */
        suspend fun runFile(
            vaultId: Long,
            target: String,
            from: String,
        ): BaseAnswer? {
            val path = resolveFile(vaultId, target, from) ?: return null
            val source = files.readText(vaultId, path) ?: return null
            // `this` is the note that embeds it, as in Obsidian; a base opened
            // on its own is its own `this`.
            return run(vaultId, source, from.ifEmpty { path }).copy(path = path)
        }

        /** Runs the YAML of a base written in [context], the note `this` names. */
        suspend fun run(
            vaultId: Long,
            source: String,
            context: String,
        ): BaseAnswer =
            withContext(Dispatchers.IO) {
                val index = load(vaultId)
                val types =
                    files
                        .readText(vaultId, VaultFilter.PROPERTY_TYPES)
                        ?.let { text ->
                            runCatching { PropertyTypes.parse(text) }
                                .onFailure {
                                    log.warn(
                                        "could not read the property types of vault $vaultId: ${it.message}",
                                    )
                                }.getOrNull()
                        }.orEmpty()
                val engine = BaseEngine(index, System.currentTimeMillis(), types)
                val tables = engine.run(Base.parse(source), context)
                BaseAnswer(BaseResult(tables, engine.problems), noteIds = index.noteIds)
            }

        private suspend fun load(vaultId: Long): VaultBaseIndex {
            val stats = notes.stats(vaultId)
            val byId = stats.associateBy { it.id }
            val others =
                blobs
                    .files(vaultId)
                    .filter { it.kind != BlobKind.MARKDOWN && (it.kind != BlobKind.CONFIG || Base.isBase(it.path)) }
            val allPaths = stats.map { it.path } + others.map { it.path }
            val tagsByPath =
                tags.usesNow(vaultId).groupBy({ byId[it.noteId]?.path }, { it.name }).filterKeys { it != null }
            val linkRows = links.inVault(vaultId)
            val linksByPath = HashMap<String, MutableList<Pair<String, Boolean>>>()
            val backlinks = HashMap<String, MutableList<String>>()
            for (link in linkRows) {
                val source = byId[link.srcId]?.path ?: continue
                if (link.kind == LinkKind.WIKILINK.name || link.kind == LinkKind.WIKI_EMBED.name) {
                    linksByPath.getOrPut(source) { mutableListOf() } +=
                        link.rawTarget to (link.kind == LinkKind.WIKI_EMBED.name)
                }
                val target = link.targetId?.let(byId::get)?.path ?: continue
                backlinks.getOrPut(target) { mutableListOf() }.let { if (source !in it) it += source }
            }
            return VaultBaseIndex(
                files = allPaths,
                noteIds = stats.associate { it.path to it.id },
                stats =
                    stats.associate {
                        it.path to
                            BaseStat(modified = it.changedAt.takeIf { at -> at > 0 }, size = it.size)
                    } +
                        others.associate { it.path to BaseStat(size = it.size) },
                tags = tagsByPath.mapKeys { it.key!! },
                links = linksByPath,
                backlinks = backlinks,
                resolver = LinkResolver(allPaths, aliases.inVault(vaultId).byPath()),
                fileOf = { path -> files.fileFor(vaultId, path) },
            )
        }
    }

/** One vault as a base sees it, read once per run. */
internal class VaultBaseIndex(
    override val files: Collection<String>,
    val noteIds: Map<String, Long>,
    private val stats: Map<String, BaseStat>,
    private val tags: Map<String, List<String>>,
    private val links: Map<String, List<Pair<String, Boolean>>>,
    private val backlinks: Map<String, List<String>>,
    private val resolver: LinkResolver,
    private val fileOf: (String) -> File,
) : BaseIndex {
    override fun note(path: String): BaseNote? {
        if (path !in noteIds) return null
        return BaseNote(
            properties = FrontMatter.parse(frontMatterOf(fileOf(path))),
            tags = tags[path].orEmpty(),
            links = links[path].orEmpty(),
        )
    }

    override fun stat(path: String): BaseStat? = stats[path]

    override fun resolve(
        target: String,
        from: String,
    ): String? = resolver.resolve(target, from)

    override fun backlinks(path: String): List<String> = backlinks[path].orEmpty()

    companion object {
        /**
         * The front matter at the top of [file] and nothing after it: a base
         * over a large vault reads the opening lines of every note it asks a
         * property of, not every note whole.
         */
        fun frontMatterOf(file: File): String {
            if (!file.isFile) return ""
            return runCatching {
                file.bufferedReader().useLines { lines ->
                    val head = StringBuilder()
                    var count = 0
                    for (line in lines) {
                        head.append(line).append('\n')
                        count++
                        if (count == 1 && line.trimEnd() != "---") return@useLines ""
                        if (count > 1 && (line.trimEnd() == "---" || line.trimEnd() == "...")) break
                        if (count > MAX_FRONT_MATTER_LINES) return@useLines ""
                    }
                    head.toString()
                }
            }.getOrDefault("")
        }

        private const val MAX_FRONT_MATTER_LINES = 1000
    }
}
