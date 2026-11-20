package me.parham1995.notes.markdown

/**
 * Resolves `[[wikilink]]` targets to vault paths, the way Obsidian does.
 *
 * One rule explains almost every link in a real vault: **a target is a suffix
 * of the file's vault-relative path, aligned on `/`, with the `.md` optional on
 * the last segment.** Bare names, partial paths and full paths are all
 * instances of it, so there is no need to special-case a category root even
 * though most links look relative to one.
 *
 * Two additions the rule alone does not cover:
 *
 *  - **Folder notes.** A folder that contains `X/X.md` answers to the folder's
 *    own path, which is how an index note links to its children.
 *  - **Case.** A handful of links only match case-insensitively. They work on
 *    the author's case-insensitive filesystem and would silently break here,
 *    so a folded index backs up the exact one.
 *
 * And one the rule is not: **aliases.** A note whose front matter says
 * `aliases: [Other name]` answers to `[[Other name]]` -- but only when no
 * file does. Obsidian gives a real name priority over an alias, so an alias
 * is consulted last, after every path and folded path has missed.
 *
 * [aliases] is keyed by path and must come from the same vault as [paths]:
 * an alias, like a link, means something only inside the vault that wrote it.
 */
class LinkResolver(
    paths: Collection<String>,
    aliases: Map<String, Collection<String>> = emptyMap(),
    /**
     * The folders that are the root of an Open Knowledge Format bundle:
     * those whose `index.md` has `okf_version` in its front matter. A `/`
     * path in a note under one is from that folder, not the vault's root.
     */
    bundleRoots: Collection<String> = emptyList(),
) {
    private val all = HashSet<String>()
    private val roots = bundleRoots.map { Slugs.normalize(it).trim('/') }.filter { it.isNotEmpty() }
    private val exact = HashMap<String, MutableList<String>>()
    private val folded = HashMap<String, MutableList<String>>()
    private val aliased = HashMap<String, MutableList<String>>()

    init {
        paths.forEach { register(Slugs.normalize(it)) }
        aliases.forEach { (path, names) ->
            val normalized = Slugs.normalize(path)
            names.forEach { name ->
                val key = Slugs.fold(name.trim())
                if (key.isNotEmpty()) {
                    aliased.getOrPut(key) { mutableListOf() }.let { if (normalized !in it) it += normalized }
                }
            }
        }
    }

    private fun register(path: String) {
        all += path
        val segments = path.split('/')
        indexSuffixes(segments, path)

        // A folder note also answers to its folder's path, so that
        // `[[Some/Folder]]` finds `Some/Folder/Folder.md`.
        val name = segments.last().removeSuffix(MD)
        if (segments.size >= 2 && segments[segments.size - 2] == name) {
            indexSuffixes(segments.dropLast(1), path)
        }
    }

    private fun indexSuffixes(
        segments: List<String>,
        path: String,
    ) {
        for (from in segments.indices) {
            val slice = segments.subList(from, segments.size)
            put(slice.joinToString("/"), path)
            val last = slice.last()
            if (last.endsWith(MD)) {
                put((slice.dropLast(1) + last.removeSuffix(MD)).joinToString("/"), path)
            }
        }
    }

    private fun put(
        key: String,
        path: String,
    ) {
        exact.getOrPut(key) { mutableListOf() }.let { if (path !in it) it += path }
        val lower = Slugs.fold(key)
        folded.getOrPut(lower) { mutableListOf() }.let { if (path !in it) it += path }
    }

    /**
     * The vault path [target] refers to, seen from [source], or null when
     * nothing matches -- a broken link, which the UI styles differently rather
     * than hiding.
     */
    fun resolve(
        target: String,
        source: String,
    ): String? {
        if (target.isEmpty()) return source
        // A Markdown link's `/path`, as the Open Knowledge Format reads it.
        if (target.startsWith("/")) return fromRoot(target, source)

        val cleaned = Slugs.normalize(target).trim().trimEnd('/')
        if (cleaned.isEmpty()) return source
        val folder = target.trim().endsWith("/")

        // A file's own path, `tables/orders.md`, or a folder's, `tables/`,
        // is from the note's folder first, as OKF reads a Markdown link. One
        // that is no file there, and a note's name, which is how a wikilink
        // is written, go on to Obsidian's rule below.
        if ((cleaned.endsWith(MD) || folder) && !cleaned.startsWith("./") && !cleaned.startsWith("../")) {
            beside(cleaned, source)?.let { return it }
        }

        // A `./` or `../` path is only what it says: the file there, or nothing.
        if (cleaned.startsWith("./") || cleaned.startsWith("../")) return relative(cleaned, source)

        val candidates = exact[cleaned] ?: folded[Slugs.fold(cleaned)] ?: aliased[Slugs.fold(cleaned)] ?: return null
        return when (candidates.size) {
            1 -> candidates.first()
            else -> disambiguate(candidates, source)
        }
    }

    /**
     * A path from the bundle's root: the nearest folder at or above the
     * note that is one, else the vault's root (`open-knowledge-format.md` in
     * notes-linux's `docs/specs/`, section 3). It is only what it says: a
     * path that climbs above its root names nothing, and one that names no
     * file there is not looked for anywhere else.
     */
    private fun fromRoot(
        target: String,
        source: String,
    ): String? {
        val root = roots.filter { source.startsWith("$it/") }.maxByOrNull { it.length }.orEmpty()
        val base = root.split('/').filter { it.isNotEmpty() }.toMutableList()
        val floor = base.size
        for (segment in Slugs.normalize(target).trim().split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (base.size > floor) base.removeAt(base.lastIndex) else return null
                else -> base += segment
            }
        }
        return file(base.joinToString("/"))
    }

    /**
     * The file a path names exactly: itself, itself with `.md`, or, for a
     * folder, its `index.md`, as a bundle's own index links to one.
     */
    private fun file(path: String): String? =
        when {
            path.isEmpty() -> null
            path in all -> path
            "$path$MD" in all -> "$path$MD"
            "$path/$INDEX" in all -> "$path/$INDEX"
            else -> null
        }

    /** The file at [target] seen from the folder of [source], when there is one exactly there. */
    private fun beside(
        target: String,
        source: String,
    ): String? {
        if (target.split('/').any { it == ".." || it == "." }) return null
        val folder = source.substringBeforeLast('/', "")
        return file(if (folder.isEmpty()) target else "$folder/$target")
    }

    private fun relative(
        target: String,
        source: String,
    ): String? {
        val base =
            source
                .substringBeforeLast('/', "")
                .split('/')
                .filter { it.isNotEmpty() }
                .toMutableList()
        for (segment in target.split('/')) {
            when (segment) {
                "." -> Unit
                ".." -> if (base.isNotEmpty()) base.removeAt(base.lastIndex) else return null
                else -> base += segment
            }
        }
        val joined = base.joinToString("/")
        return file(joined)
    }

    /**
     * Measured against a real vault, preferring the candidate sharing the
     * longest directory prefix with the source resolves about four fifths of
     * ambiguous links; the rest are genuine authoring ambiguities where any
     * answer is a guess, so the remaining rules exist to make the guess stable.
     */
    private fun disambiguate(
        candidates: List<String>,
        source: String,
    ): String {
        val sourceDirs = source.split('/').dropLast(1)
        return candidates.minWithOrNull(
            compareByDescending<String> { sharedPrefix(sourceDirs, it.split('/').dropLast(1)) }
                .thenBy { it.count { ch -> ch == '/' } }
                .thenBy { it.length }
                .thenBy { it },
        ) ?: candidates.first()
    }

    private fun sharedPrefix(
        a: List<String>,
        b: List<String>,
    ): Int {
        var shared = 0
        while (shared < a.size && shared < b.size && a[shared] == b[shared]) shared++
        return shared
    }

    private companion object {
        const val INDEX = "index.md"
        const val MD = ".md"
    }
}
