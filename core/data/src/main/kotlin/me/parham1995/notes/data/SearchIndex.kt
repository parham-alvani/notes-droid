package me.parham1995.notes.data

import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import me.parham1995.notes.data.database.NotesDatabase
import javax.inject.Inject
import javax.inject.Singleton

data class SearchHit(
    val noteId: Long,
    val title: String,
    val path: String,
    val snippet: String,
)

/** One note as the search index holds it. */
data class SearchDocument(
    val noteId: Long,
    val title: String,
    val body: String,
)

/**
 * The FTS5 index, driven through raw SQL.
 *
 * Room cannot own this table -- it only annotates FTS3 and FTS4, and neither
 * can rank results. `bm25()` with the title weighted an order of magnitude
 * above the body is what makes a search for a note's own name return that note
 * first, and `snippet()` gives the excerpt for free rather than hand-rolling
 * one from the body text.
 */
@Singleton
class SearchIndex
    @Inject
    constructor(
        private val database: NotesDatabase,
    ) {
        suspend fun upsert(
            noteId: Long,
            title: String,
            body: String,
        ) = upsertAll(listOf(SearchDocument(noteId, title, body)))

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
                    usePrepared("INSERT INTO $FTS(rowid, title, body) VALUES (?, ?, ?)") { statement ->
                        documents.forEach { document ->
                            statement.bindLong(1, document.noteId)
                            statement.bindText(2, document.title)
                            statement.bindText(3, document.body)
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

        suspend fun search(
            vaultId: Long,
            raw: String,
            limit: Int = DEFAULT_LIMIT,
        ): List<SearchHit> {
            val query = FtsQuery.sanitize(raw) ?: return emptyList()
            return database.useReaderConnection { connection ->
                connection.usePrepared(SEARCH_SQL) { statement ->
                    statement.bindText(1, query)
                    statement.bindLong(2, vaultId)
                    statement.bindLong(3, limit.toLong())
                    buildList {
                        while (statement.step()) {
                            add(
                                SearchHit(
                                    noteId = statement.getLong(0),
                                    title = statement.getText(1),
                                    path = statement.getText(2),
                                    snippet = statement.getText(3),
                                ),
                            )
                        }
                    }
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
                        buildList {
                            while (statement.step()) {
                                add(
                                    SearchHit(
                                        noteId = statement.getLong(0),
                                        title = statement.getText(1),
                                        path = statement.getText(2),
                                        snippet = statement.getText(3),
                                    ),
                                )
                            }
                        }
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
                SELECT notes.id, notes.title, notes.path,
                       snippet(note_fts, 1, '[', ']', '...', 14)
                FROM note_fts
                JOIN notes ON notes.id = note_fts.rowid
                WHERE note_fts MATCH ? AND notes.vaultId = ?
                ORDER BY bm25(note_fts, 10.0, 1.0)
                LIMIT ?
                """.trimIndent()
        }
    }

/**
 * Turns what someone types into something FTS5 will accept.
 *
 * This is not cosmetic. FTS5 treats `"`, `*`, `:`, `-`, `^`, `(`, `)` and the
 * bare words `AND`, `OR`, `NOT` and `NEAR` as query syntax, so passing raw
 * input through throws on entirely ordinary searches -- a hyphenated word, or
 * a stray quote mid-typing.
 */
object FtsQuery {
    private val TOKEN = Regex("""[\p{L}\p{N}_]+""")

    /**
     * The same text as an exact phrase rather than a bag of words.
     *
     * FTS5 reads a double-quoted string as a phrase, so the tokens are
     * re-quoted rather than passed through -- the input is a note title and
     * can contain anything a filename can.
     */
    fun phrase(raw: String): String? {
        val tokens = TOKEN.findAll(raw).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ", prefix = "\"", postfix = "\"")
    }

    /** Null when there is nothing left worth searching for. */
    fun sanitize(raw: String): String? {
        val tokens = TOKEN.findAll(raw).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        // Quote every token so nothing is read as an operator, and make the
        // last one a prefix so results appear while still typing.
        return tokens
            .mapIndexed { index, token ->
                val quoted = "\"" + token + "\""
                if (index == tokens.lastIndex) "$quoted*" else quoted
            }.joinToString(" ")
    }
}
