package me.parham1995.notes.sync

import java.time.Instant

/**
 * One commit that changed a note: who, when, why, and where the note was
 * then -- a note renamed since was somewhere else, and its text at that
 * commit is read from there.
 */
data class NoteVersion(
    val commit: String,
    val date: Instant,
    val author: String,
    val subject: String,
    /** The note's path at this commit. */
    val path: String,
)

/**
 * A note's history, as `git log --follow` gives it: the commits that changed
 * it, newest first, across renames; and its text as it was at one. The rule
 * Daftar for Linux and the Mac keep (`Git::history` and `Git::content` in
 * notes-linux, `Git.swift` in notes-mac).
 */
interface NoteHistory {
    suspend fun history(path: String): List<NoteVersion>

    /** The note as it was at [version], or null when that commit does not have it. */
    suspend fun content(version: NoteVersion): String?
}

/**
 * The history over GitHub's REST API, for a vault synced without a clone.
 *
 * GitHub's list of a path's commits stops at a rename, as `git log -- path`
 * does; `--follow` is made here by asking the oldest commit listed whether it
 * renamed the note, and going on from the commit before it under the old
 * name. At most [limit] commits and [renames] renames, so a long history is
 * a few requests rather than a crawl.
 */
class RestNoteHistory(
    private val client: GitHubClient,
    private val branch: String,
    private val limit: Int = DEFAULT_LIMIT,
    private val renames: Int = DEFAULT_RENAMES,
) : NoteHistory {
    override suspend fun history(path: String): List<NoteVersion> {
        val out = mutableListOf<NoteVersion>()
        var current = path
        var ref = branch
        repeat(renames + 1) {
            val page = client.commits(current, ref, perPage = minOf(limit - out.size, PAGE))
            out += page
            val oldest = page.lastOrNull()
            // A full page has older commits behind it under the same name;
            // past the limit, they are not asked for.
            if (oldest == null || out.size >= limit || page.size == PAGE) return out
            val (before, parent) = client.renamedFrom(oldest.commit, current) ?: return out
            current = before
            ref = parent
        }
        return out
    }

    override suspend fun content(version: NoteVersion): String? = client.file(version.path, version.commit)?.text

    private companion object {
        const val PAGE = 100
        const val DEFAULT_LIMIT = 100
        const val DEFAULT_RENAMES = 10
    }
}
