package me.parham1995.notes.data.database

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Tags, always through the note that carries them.
 *
 * `tags` has no vault of its own, so every query here joins `notes` and asks
 * for one vault by id. Two vaults may use the same tag and mean nothing by it
 * together.
 */
@Dao
interface TagDao {
    /**
     * Every tag in one vault with the note it is on, one row per pair.
     *
     * Rows rather than counts because a parent's count is the number of notes
     * under it, and a note tagged `a/b` and `a/c` is one note under `a`, not
     * two -- which is a set, not a sum, and is built from these.
     */
    @Query(
        """
        SELECT tags.name AS name, tags.noteId AS noteId
        FROM tags JOIN notes ON notes.id = tags.noteId
        WHERE notes.vaultId = :vaultId
        """,
    )
    fun uses(vaultId: Long): Flow<List<TagUse>>

    /**
     * The notes carrying [folded] or anything nested under it, once each.
     *
     * [nested] must come through [escapeLike]: a tag can hold `_`, which is a
     * wildcard to LIKE.
     */
    @Query(
        """
        SELECT DISTINCT notes.* FROM tags JOIN notes ON notes.id = tags.noteId
        WHERE notes.vaultId = :vaultId
          AND (tags.folded = :folded OR tags.folded LIKE :nested || '/%' ESCAPE '\')
        ORDER BY notes.name COLLATE NOCASE
        """,
    )
    suspend fun notesTagged(
        vaultId: Long,
        folded: String,
        nested: String,
    ): List<NoteEntity>
}

data class TagUse(
    val name: String,
    val noteId: Long,
)

@Dao
interface AliasDao {
    /** Every alias in one vault, with the path of the note it names. */
    @Query(
        """
        SELECT notes.path AS path, aliases.alias AS alias
        FROM aliases JOIN notes ON notes.id = aliases.noteId
        WHERE notes.vaultId = :vaultId
        """,
    )
    suspend fun inVault(vaultId: Long): List<AliasRow>
}

data class AliasRow(
    val path: String,
    val alias: String,
)

/** Aliases keyed by the path of their note, as the link resolver takes them. */
fun List<AliasRow>.byPath(): Map<String, List<String>> = groupBy({ it.path }, { it.alias })
