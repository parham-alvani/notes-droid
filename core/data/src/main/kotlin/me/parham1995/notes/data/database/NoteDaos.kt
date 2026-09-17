package me.parham1995.notes.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Upsert
    suspend fun upsert(note: NoteEntity): Long

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE path = :path")
    suspend fun byPath(path: String): NoteEntity?

    @Query("SELECT id FROM notes WHERE path = :path")
    suspend fun idOf(path: String): Long?

    @Query("SELECT id, path FROM notes")
    suspend fun allIds(): List<NoteRef>

    /** Notes directly inside [parent], folders excluded -- those are derived. */
    @Query("SELECT * FROM notes WHERE parent = :parent ORDER BY name COLLATE NOCASE")
    suspend fun childrenOf(parent: String): List<NoteEntity>

    /**
     * Every distinct directory. Only a few hundred rows even for a large vault,
     * so the browser builds its folder tree from this once instead of running a
     * recursive query per level.
     */
    @Query("SELECT DISTINCT parent FROM notes WHERE parent != ''")
    suspend fun allParents(): List<String>

    @Query("SELECT * FROM notes WHERE openedAt IS NOT NULL ORDER BY openedAt DESC LIMIT :limit")
    fun recentlyOpened(limit: Int): Flow<List<NoteEntity>>

    @Query("UPDATE notes SET openedAt = :at WHERE id = :id")
    suspend fun markOpened(
        id: Long,
        at: Long,
    )

    /** Fast prefix match on the name, for the quick switcher. */
    @Query(
        """
        SELECT * FROM notes
        WHERE slug LIKE :prefix || '%' OR slug LIKE '%' || :prefix || '%'
        ORDER BY (CASE WHEN slug LIKE :prefix || '%' THEN 0 ELSE 1 END), length(name), name COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun searchByName(
        prefix: String,
        limit: Int,
    ): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes")
    fun count(): Flow<Int>

    @Query("DELETE FROM notes WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM notes")
    suspend fun clear()
}

data class NoteRef(
    val id: Long,
    val path: String,
)

@Dao
interface LinkDao {
    @Insert
    suspend fun insertAll(links: List<LinkEntity>)

    @Query("DELETE FROM links WHERE srcId = :srcId")
    suspend fun deleteBySource(srcId: Long)

    /**
     * Backlinks. This one indexed query is the whole feature, which is why the
     * context line is stored on the row rather than read back from the file.
     */
    @Query(
        """
        SELECT notes.id AS noteId, notes.title AS title, notes.path AS path,
               links.context AS context
        FROM links JOIN notes ON notes.id = links.srcId
        WHERE links.targetId = :targetId
        ORDER BY notes.title COLLATE NOCASE, links.ordinal
        """,
    )
    suspend fun backlinks(targetId: Long): List<BacklinkRow>

    @Query("SELECT COUNT(*) FROM links WHERE targetId = :targetId")
    suspend fun backlinkCount(targetId: Long): Int

    /** Second indexing pass: attach every link to the note it points at. */
    @Query("UPDATE links SET targetId = :targetId WHERE id = :id")
    suspend fun setTarget(
        id: Long,
        targetId: Long?,
    )

    @Query("SELECT id, srcId, rawTarget FROM links WHERE targetId IS NULL")
    suspend fun unresolved(): List<UnresolvedLink>

    @Query("DELETE FROM links")
    suspend fun clear()
}

data class BacklinkRow(
    val noteId: Long,
    val title: String,
    val path: String,
    val context: String,
)

data class UnresolvedLink(
    val id: Long,
    val srcId: Long,
    val rawTarget: String,
)

@Dao
interface HeadingDao {
    @Insert
    suspend fun insertAll(headings: List<HeadingEntity>)

    @Query("SELECT * FROM headings WHERE noteId = :noteId ORDER BY ordinal")
    suspend fun byNote(noteId: Long): List<HeadingEntity>

    @Query("DELETE FROM headings WHERE noteId = :noteId")
    suspend fun deleteByNote(noteId: Long)

    @Query("DELETE FROM headings")
    suspend fun clear()
}

/**
 * Full-text search over FTS5.
 *
 * Every query here is a [RawQuery]: the virtual table is created by hand in the
 * database callback rather than declared as an entity, so Room cannot verify
 * statements against it at compile time. Room only annotates FTS3 and FTS4, and
 * FTS4 has no ranking function at all -- `bm25()` with a heavier weight on the
 * title is the difference between useful results and arbitrary ones.
 */
@Dao
interface SearchDao {
    @RawQuery
    suspend fun rawSearch(query: RoomRawQuery): List<SearchRow>

    @RawQuery
    suspend fun rawExecute(query: RoomRawQuery): List<SearchRow>
}

data class SearchRow(
    val noteId: Long,
    val title: String,
    val path: String,
    val snippet: String,
)
