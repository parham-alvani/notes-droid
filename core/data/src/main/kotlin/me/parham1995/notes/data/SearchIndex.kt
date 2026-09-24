package me.parham1995.notes.data

import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import me.parham1995.notes.data.database.NotesDatabase
import me.parham1995.notes.data.database.escapeLike
import me.parham1995.notes.markdown.SearchText
import javax.inject.Inject
import javax.inject.Singleton

data class SearchHit(
    val noteId: Long,
    val title: String,
    val path: String,
    val snippet: String,
)

/** One note as the search index is given it, before normalizing. */
data class SearchDocument(
    val noteId: Long,
    val title: String,
    val body: String,
    /** Vault-relative, for `path:`. */
    val path: String,
    val aliases: List<String> = emptyList(),
)

/**
 * The FTS5 index, driven through raw SQL.
 *
 * Room cannot own this table -- it only annotates FTS3 and FTS4, and neither
 * can rank results. `bm25()` with the title weighted an order of magnitude
 * above the body is what makes a search for a note's own name return that note
 * first, and `snippet()` gives the excerpt for free rather than hand-rolling
 * one from the body text.
 *
 * Everything written here and everything asked of it goes through
 * [SearchText.normalize], so Arabic and Persian letter forms, both families of
 * digits and words written with or without a half-space all meet. The index
 * therefore holds normalized text, and an excerpt of it is carried back to the
 * note's own characters by [SearchText.restoreSnippet] before anyone sees it.
 */
@Singleton
class SearchIndex
    @Inject
    constructor(
        private val database: NotesDatabase,
    ) {
        suspend fun upsert(document: SearchDocument) = upsertAll(listOf(document))

        /**
         * Replaces many notes' entries in one transaction.
         *
         * A statement outside a transaction is its own commit, and a commit is
         * an fsync: indexed one note at a time, a vault of 4,600 notes paid for
         * 4,600 of them after the rest of the index had been written in
         * batches of two hundred.
         */
        suspend fun upsertAll(documents: List<SearchDocument>) {
            if (documents.isEmpty()) return
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    usePrepared("DELETE FROM $FTS WHERE rowid = ?") { statement ->
                        documents.forEach { document ->
                            statement.bindLong(1, document.noteId)
                            statement.step()
                            statement.reset()
                        }
                    }
                    usePrepared(
                        "INSERT INTO $FTS(rowid, title, body, original, path, name, aliases) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    ) { statement ->
                        documents.forEach { document ->
                            val body = SearchText.normalize(document.body)
                            statement.bindLong(1, document.noteId)
                            statement.bindText(2, SearchText.normalize(document.title))
                            statement.bindText(3, body)
                            // Only when it differs: for most notes it would be
                            // a second copy of the body for nothing.
                            if (body == document.body) statement.bindNull(4) else statement.bindText(4, document.body)
                            statement.bindText(5, SearchText.normalize(document.path))
                            statement.bindText(6, SearchText.foldName(document.title))
                            statement.bindText(7, aliasLines(document.aliases))
                            statement.step()
                            statement.reset()
                        }
                    }
                }
            }
        }

        suspend fun delete(noteId: Long) {
            database.useWriterConnection { connection ->
                connection.usePrepared("DELETE FROM $FTS WHERE rowid = ?") { statement ->
                    statement.bindLong(1, noteId)
                    statement.step()
                }
            }
        }

        /** Drops many notes in one transaction, rather than a commit each. */
        suspend fun deleteAll(noteIds: Collection<Long>) {
            if (noteIds.isEmpty()) return
            database.useWriterConnection { transactor ->
                transactor.immediateTransaction {
                    usePrepared("DELETE FROM $FTS WHERE rowid = ?") { statement ->
                        noteIds.forEach { id ->
                            statement.bindLong(1, id)
                            statement.step()
                            statement.reset()
                        }
                    }
                }
            }
        }

        suspend fun clear() {
            database.useWriterConnection { connection ->
                connection.usePrepared("DELETE FROM $FTS") { it.step() }
            }
        }

        /** Merges the index's b-trees after a batch, keeping queries fast. */
        suspend fun optimize() {
            database.useWriterConnection { connection ->
                connection.usePrepared("INSERT INTO $FTS($FTS) VALUES ('optimize')") { it.step() }
            }
        }

        /** Drops one vault's rows, leaving the others searchable. */
        suspend fun clearVault(vaultId: Long) {
            database.useWriterConnection { connection ->
                connection.usePrepared(
                    "DELETE FROM $FTS WHERE rowid IN (SELECT id FROM notes WHERE vaultId = ?)",
                ) { statement ->
                    statement.bindLong(1, vaultId)
                    statement.step()
                }
            }
        }

        /**
         * What [raw] finds in one vault, read with the operators Obsidian's
         * own search has: a quoted phrase, `-word` to leave notes out, and
         * `path:folder` to look only under a folder. See [FtsQuery.parse].
         */
        suspend fun search(
            vaultId: Long,
            raw: String,
            limit: Int = DEFAULT_LIMIT,
        ): List<SearchHit> {
            val query = FtsQuery.parse(raw) ?: return emptyList()
            val (sql, arguments) = query.sql(vaultId, limit)
            return database.useReaderConnection { connection ->
                connection.usePrepared(sql) { statement ->
                    arguments.forEachIndexed { index, argument ->
                        when (argument) {
                            is Long -> statement.bindLong(index + 1, argument)
                            else -> statement.bindText(index + 1, argument.toString())
                        }
                    }
                    buildList { while (statement.step()) add(statement.hit()) }
                }
            }
        }

        /**
         * Notes whose name or alias contains [typed], for the quick switcher,
         * as ids in the order to offer them.
         *
         * Compared as [SearchText.foldName] leaves both sides, so a name
         * written with Arabic letters answers to a Persian keyboard. That
         * needs the folded names somewhere to compare against, and they are
         * kept in the search index rather than in a new column of `notes`:
         * that would be a Room migration for what is only ever search's
         * business.
         *
         * A name that starts with it first, then an alias that does, then
         * anything containing it -- as Obsidian's switcher does. Scoped to
         * one vault like everything else: this was the last query in the app
         * that was not, and typing a name reached across every repository.
         */
        suspend fun names(
            vaultId: Long,
            typed: String,
            limit: Int,
        ): List<Long> {
            // `%` and `_` are wildcards to LIKE and ordinary characters in a
            // file name, so typing "100%" offered every note starting "100".
            val folded = escapeLike(SearchText.foldName(typed.trim()))
            if (folded.isEmpty()) return emptyList()
            return database.useReaderConnection { connection ->
                connection.usePrepared(NAMES_SQL) { statement ->
                    statement.bindLong(1, vaultId)
                    statement.bindText(2, folded)
                    statement.bindLong(3, limit.toLong())
                    buildList { while (statement.step()) add(statement.getLong(0)) }
                }
            }
        }

        /**
         * Notes that say this note's name without linking to it.
         *
         * Obsidian calls these unlinked mentions, and in a vault where 110
         * basenames are duplicated and links are written by hand they are how
         * you find the connection somebody meant to make. It is the same index
         * search does, restricted to the title and with everything that
         * already links here taken out.
         */
        suspend fun mentions(
            vaultId: Long,
            title: String,
            exclude: Set<Long>,
            limit: Int = MENTION_LIMIT,
        ): List<SearchHit> {
            // Phrase-matched, not tokenised: a note called "Rate Limiting"
            // should find that phrase, not every note containing "rate".
            val phrase = FtsQuery.phrase(title) ?: return emptyList()
            return database
                .useReaderConnection { connection ->
                    connection.usePrepared(SEARCH_SQL) { statement ->
                        statement.bindText(1, phrase)
                        statement.bindLong(2, vaultId)
                        // Asked for generously, because the exclusions are
                        // applied after ranking rather than in SQL -- the
                        // linked set is small and already in memory.
                        statement.bindLong(3, (limit * OVERSCAN).toLong())
                        buildList { while (statement.step()) add(statement.hit()) }
                    }
                }.filterNot { it.noteId in exclude }
                .take(limit)
        }

        private companion object {
            const val FTS = NotesDatabase.FTS_TABLE
            const val MENTION_LIMIT = 30
            const val OVERSCAN = 3
            const val DEFAULT_LIMIT = 100

            val SEARCH_SQL =
                """
                SELECT notes.id, notes.title, notes.path, $SNIPPET, note_fts.original
                FROM note_fts
                JOIN notes ON notes.id = note_fts.rowid
                WHERE note_fts MATCH ? AND notes.vaultId = ?
                ORDER BY bm25(note_fts, 10.0, 1.0)
                LIMIT ?
                """.trimIndent()

            val NAMES_SQL =
                """
                SELECT notes.id FROM notes JOIN note_fts ON note_fts.rowid = notes.id
                WHERE notes.vaultId = ?1
                  AND (note_fts.name LIKE '%' || ?2 || '%' ESCAPE '\'
                       OR note_fts.aliases LIKE '%' || ?2 || '%' ESCAPE '\')
                ORDER BY (CASE WHEN note_fts.name LIKE ?2 || '%' ESCAPE '\' THEN 0
                               WHEN note_fts.aliases LIKE '%' || char(10) || ?2 || '%' ESCAPE '\' THEN 1
                               WHEN note_fts.name LIKE '%' || ?2 || '%' ESCAPE '\' THEN 2
                               ELSE 3 END),
                         length(notes.name), notes.name COLLATE NOCASE
                LIMIT ?3
                """.trimIndent()

            /** One alias to a line, with a newline either side, so "starts with" can be asked of it. */
            fun aliasLines(aliases: List<String>): String =
                if (aliases.isEmpty()) "" else aliases.joinToString("\n", "\n", "\n") { SearchText.foldName(it) }

            /** A row of a search, with its excerpt shown in the note's own words. */
            fun SQLiteStatement.hit() =
                SearchHit(
                    noteId = getLong(0),
                    title = getText(1),
                    path = getText(2),
                    snippet = SearchText.restoreSnippet(getText(3), if (isNull(4)) null else getText(4)),
                )
        }
    }

/**
 * The excerpt FTS5 makes from the body, marked with private-use characters
 * that [SearchText.restoreSnippet] turns back into `[`, `]` and `...` once it
 * has found the note's own characters.
 */
internal val SNIPPET =
    "snippet(note_fts, 1, '${SearchText.SNIPPET_OPEN}', '${SearchText.SNIPPET_CLOSE}', " +
        "'${SearchText.SNIPPET_ELLIPSIS}', 14)"

/**
 * A search as someone typed it, taken apart.
 *
 * [include] and [exclude] are FTS5 expressions built only from re-quoted
 * tokens, so nothing typed reaches FTS5 as syntax. [paths] and
 * [excludedPaths] are vault-relative prefixes, applied in SQL beside the
 * vault's own id -- a folder is only ever a folder of the vault being searched.
 * All of it is already [SearchText.normalize]d, as the index is.
 */
data class ParsedSearch(
    /** What a note must say, or null when only a folder narrows it. */
    val include: String?,
    /** What a note must not say, or null. */
    val exclude: String? = null,
    val paths: List<String> = emptyList(),
    val excludedPaths: List<String> = emptyList(),
) {
    /** The FTS5 expression, with the exclusions folded in. */
    val match: String?
        get() =
            when {
                include == null -> null
                exclude == null -> include
                else -> "($include) NOT ($exclude)"
            }

    /** The statement for one vault, and its arguments in order. */
    internal fun sql(
        vaultId: Long,
        limit: Int,
    ): Pair<String, List<Any>> {
        val arguments = mutableListOf<Any>()
        val text = match
        val select =
            if (text != null) {
                arguments += text
                "SELECT notes.id, notes.title, notes.path, $SNIPPET, note_fts.original " +
                    "FROM note_fts JOIN notes ON notes.id = note_fts.rowid " +
                    "WHERE note_fts MATCH ? AND notes.vaultId = ?"
            } else {
                // Only a folder: nothing to rank by, so it is listed in order.
                "SELECT notes.id, notes.title, notes.path, '', NULL " +
                    "FROM notes JOIN note_fts ON note_fts.rowid = notes.id WHERE notes.vaultId = ?"
            }
        arguments += vaultId
        val where = StringBuilder()
        if (paths.isNotEmpty()) {
            // The index's own copy of the path, normalized as the query is.
            where.append(paths.joinToString(" OR ", " AND (", ")") { "note_fts.path LIKE ? ESCAPE '\\'" })
            paths.forEach { arguments += escapeLike(it) + "%" }
        }
        excludedPaths.forEach {
            where.append(" AND note_fts.path NOT LIKE ? ESCAPE '\\'")
            arguments += escapeLike(it) + "%"
        }
        if (text == null && exclude != null) {
            // The exclusion still goes through the index, just not as a match.
            where.append(" AND notes.id NOT IN (SELECT rowid FROM note_fts WHERE note_fts MATCH ?)")
            arguments += exclude
        }
        val order = if (text != null) " ORDER BY bm25(note_fts, 10.0, 1.0)" else " ORDER BY notes.path"
        arguments += limit.toLong()
        return "$select$where$order LIMIT ?" to arguments
    }
}

/**
 * Turns what someone types into something FTS5 will accept.
 *
 * This is not cosmetic. FTS5 treats `"`, `*`, `:`, `-`, `^`, `(`, `)` and the
 * bare words `AND`, `OR`, `NOT` and `NEAR` as query syntax, so passing raw
 * input through throws on entirely ordinary searches -- a hyphenated word, or
 * a stray quote mid-typing. So every token is re-quoted, and the few operators
 * understood here are rebuilt from scratch rather than passed along.
 */
object FtsQuery {
    private val TOKEN = Regex("""[\p{L}\p{N}_]+""")
    private const val PATH = "path:"

    /**
     * The same text as an exact phrase rather than a bag of words.
     *
     * FTS5 reads a double-quoted string as a phrase, so the tokens are
     * re-quoted rather than passed through -- the input is a note title and
     * can contain anything a filename can.
     */
    fun phrase(raw: String): String? {
        val tokens = TOKEN.findAll(SearchText.normalize(raw)).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ", prefix = "\"", postfix = "\"")
    }

    /**
     * Whether [raw] uses an operator [parse] reads -- which is when looking
     * for a note by its name stops making sense.
     */
    fun usesOperators(raw: String): Boolean = terms(raw).any { it.negated || it.path || it.quoted }

    /**
     * What [raw] asks for, or null when nothing is left worth searching for.
     *
     * - Words match as they always have, the last one as a prefix so results
     *   appear while it is still being typed.
     * - `"an exact phrase"` matches as written. An unclosed quote runs to the
     *   end, which is what one looks like mid-typing.
     * - `-word` or `-"a phrase"` leaves out the notes that say it.
     * - `path:Folder/Sub` keeps notes whose path starts there, `path:"With
     *   Spaces"` quotes one, and `-path:` leaves a folder out.
     *
     * Exclusions alone ask for nothing: FTS5 has no "everything but", and a
     * whole vault minus one word is not a search anyone means.
     *
     * [raw] is normalized first, as the index is, so every one of these --
     * words, phrases, exclusions and folders -- meets the note whichever
     * keyboard wrote either side.
     */
    fun parse(raw: String): ParsedSearch? {
        @Suppress("NAME_SHADOWING")
        val raw = SearchText.normalize(raw)
        val include = mutableListOf<String>()
        val exclude = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val excludedPaths = mutableListOf<String>()
        val found = terms(raw)
        val last = found.indexOfLast { !it.negated && !it.path }
        found.forEachIndexed { index, term ->
            if (term.path) {
                val prefix = term.text.trim().trim('/')
                if (prefix.isNotEmpty()) (if (term.negated) excludedPaths else paths) += prefix
                return@forEachIndexed
            }
            val tokens = TOKEN.findAll(term.text).map { it.value }.toList()
            if (tokens.isEmpty()) return@forEachIndexed
            val typing = index == last && !term.closed
            when {
                // One thing to leave out, however many tokens it spans.
                term.negated -> exclude += quoted(tokens)
                term.quoted -> include += quoted(tokens) + if (typing) "*" else ""
                else ->
                    tokens.forEachIndexed { at, token ->
                        include += quoted(listOf(token)) + if (typing && at == tokens.lastIndex) "*" else ""
                    }
            }
        }
        if (include.isEmpty() && paths.isEmpty()) return null
        return ParsedSearch(
            include = include.takeIf { it.isNotEmpty() }?.joinToString(" "),
            exclude = exclude.takeIf { it.isNotEmpty() }?.joinToString(" OR "),
            paths = paths,
            excludedPaths = excludedPaths,
        )
    }

    private fun quoted(tokens: List<String>) = tokens.joinToString(" ", "\"", "\"")

    private class Term(
        val text: String,
        val negated: Boolean,
        val path: Boolean,
        val quoted: Boolean,
        /** Ended by a closing quote or a space, so no longer being typed. */
        val closed: Boolean,
    )

    /** Splits [raw] on whitespace, keeping a quoted run together. */
    private fun terms(raw: String): List<Term> {
        val out = mutableListOf<Term>()
        var i = 0
        while (i < raw.length) {
            if (raw[i].isWhitespace()) {
                i++
                continue
            }
            // A dash leads an exclusion only at the start of a term: inside a
            // word it is a hyphen, and alone it is nothing.
            val negated = raw[i] == '-' && i + 1 < raw.length && !raw[i + 1].isWhitespace()
            if (negated) i++
            val path = raw.regionMatches(i, PATH, 0, PATH.length, ignoreCase = true)
            if (path) i += PATH.length
            val quoted = i < raw.length && raw[i] == '"'
            val end: Int
            if (quoted) {
                val close = raw.indexOf('"', i + 1)
                end = if (close < 0) raw.length else close
                out += Term(raw.substring(i + 1, end), negated, path, quoted = true, closed = close >= 0)
                i = end + 1
            } else {
                end = (i until raw.length).firstOrNull { raw[it].isWhitespace() } ?: raw.length
                out += Term(raw.substring(i, end), negated, path, quoted = false, closed = end < raw.length)
                i = end
            }
        }
        return out
    }
}
