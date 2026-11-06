package me.parham1995.notes.data.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.parham1995.notes.sync.NoteHistory
import me.parham1995.notes.sync.NoteVersion
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffConfig
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.FollowFilter
import org.eclipse.jgit.revwalk.RenameCallback
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

/**
 * A note's history from the vault's own clone, as `git log --follow` lists
 * it: the commits that changed the note, newest first, across renames, each
 * with the path the note had then.
 *
 * The clone is shallow -- one commit deep, to keep a vault to a size a phone
 * can hold -- so the history goes back as far as the clone does and no
 * further. A note changed since the clone was made has those commits too,
 * since every sync fetches what is new.
 */
class GitNoteHistory(
    private val workTree: File,
    private val branch: String,
    private val limit: Int = DEFAULT_LIMIT,
) : NoteHistory {
    override suspend fun history(path: String): List<NoteVersion> =
        withContext(Dispatchers.IO) { Git.open(workTree).use { log(it.repository, path) } }

    override suspend fun content(version: NoteVersion): String? =
        withContext(
            Dispatchers.IO,
        ) { Git.open(workTree).use { contentAt(it.repository, version.commit, version.path) } }

    private fun log(
        repository: Repository,
        path: String,
    ): List<NoteVersion> {
        val start =
            repository.resolve(Constants.R_REMOTES + Constants.DEFAULT_REMOTE_NAME + "/" + branch)
                ?: repository.resolve(Constants.HEAD)
                ?: return emptyList()
        return RevWalk(repository).use { walk ->
            // Each rename as the walk finds it, newest first. The walk looks
            // a commit ahead, so a rename is heard of before the commit that
            // made it is handed over; which commit has which path is decided
            // afterwards, by asking each commit's tree.
            val renames = ArrayDeque<DiffEntry>()
            val follow = FollowFilter.create(path, repository.config.get(DiffConfig.KEY))
            follow.renameCallback =
                object : RenameCallback() {
                    override fun renamed(entry: DiffEntry) {
                        renames.addLast(entry)
                    }
                }
            walk.treeFilter = follow
            walk.markStart(walk.parseCommit(start))
            val commits = walk.take(limit)
            var current = path
            commits.map { commit ->
                val rename = renames.firstOrNull()
                if (rename != null && rename.newPath == current && !has(repository, commit, current)) {
                    current = rename.oldPath
                    renames.removeFirst()
                }
                NoteVersion(
                    commit = commit.name,
                    date = commit.authorIdent.whenAsInstant,
                    author = commit.authorIdent.name,
                    subject = commit.shortMessage,
                    path = current,
                )
            }
        }
    }

    private fun has(
        repository: Repository,
        commit: RevCommit,
        path: String,
    ): Boolean = TreeWalk.forPath(repository, path, commit.tree)?.use { true } ?: false

    private fun contentAt(
        repository: Repository,
        commit: String,
        path: String,
    ): String? {
        val id = repository.resolve(commit) ?: return null
        // A full sha resolves whether or not the clone has it.
        if (!repository.objectDatabase.has(id)) return null
        return RevWalk(repository).use { walk ->
            TreeWalk.forPath(repository, path, walk.parseCommit(id).tree)?.use { found ->
                String(repository.open(found.getObjectId(0)).bytes)
            }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
    }
}
