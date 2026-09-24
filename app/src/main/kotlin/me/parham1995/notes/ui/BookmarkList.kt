package me.parham1995.notes.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.VaultBookmarks
import me.parham1995.notes.obsidian.Bookmark

/**
 * One line of a bookmark list: the bookmark, where it sits, and -- for a note
 * -- the note it opens, or null when that note is not in this vault.
 */
data class BookmarkNode(
    val bookmark: Bookmark,
    /** Its position in the tree, which is what a group is opened and shut by. */
    val key: String,
    val depth: Int,
    val noteId: Long? = null,
)

/**
 * The tree as rows: a group's contents follow it, one step in, while it is
 * [open]. Groups start shut, because a vault's bookmarks can run long and the
 * list sits above everything else on the screen.
 */
fun bookmarkNodes(
    bookmarks: VaultBookmarks,
    open: Set<String>,
): List<BookmarkNode> {
    val out = mutableListOf<BookmarkNode>()

    fun walk(
        items: List<Bookmark>,
        parent: String,
        depth: Int,
    ) {
        items.forEachIndexed { index, bookmark ->
            val key = if (parent.isEmpty()) "$index" else "$parent/$index"
            val noteId = (bookmark as? Bookmark.File)?.let { bookmarks.noteIds[it.path] }
            out += BookmarkNode(bookmark, key, depth, noteId)
            if (bookmark is Bookmark.Group && key in open) walk(bookmark.items, key, depth + 1)
        }
    }
    walk(bookmarks.items, "", 0)
    return out
}

/** What a bookmark can ask for. Each place that lists them decides what those mean there. */
class BookmarkActions(
    val onNote: (noteId: Long, heading: String?) -> Unit,
    val onFolder: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onUrl: (String) -> Unit,
    /** A note that is not in this vault, by the path it was bookmarked at. */
    val onMissing: (String) -> Unit,
    val onToggleGroup: (String) -> Unit,
)

fun BookmarkNode.open(actions: BookmarkActions) {
    when (val target = bookmark) {
        is Bookmark.File -> noteId?.let { actions.onNote(it, target.heading) } ?: actions.onMissing(target.path)
        is Bookmark.Folder -> actions.onFolder(target.path)
        is Bookmark.Search -> actions.onSearch(target.query)
        is Bookmark.Url -> actions.onUrl(target.url)
        is Bookmark.Group -> actions.onToggleGroup(key)
    }
}

/** The rows, for a lazy list that shows bookmarks among other things. */
fun LazyListScope.bookmarkItems(
    nodes: List<BookmarkNode>,
    open: Set<String>,
    actions: BookmarkActions,
    keyPrefix: String = "bookmark",
) {
    items(nodes, key = { "$keyPrefix-${it.key}" }) { node ->
        val bookmark = node.bookmark
        ItemRow(
            title = bookmark.label,
            icon = null,
            defaultIcon =
                when (bookmark) {
                    is Bookmark.File -> if (bookmark.heading != null) "heading" else "file-text"
                    is Bookmark.Folder -> "folder"
                    is Bookmark.Search -> "search"
                    is Bookmark.Url -> "link"
                    is Bookmark.Group -> if (node.key in open) "chevron-down" else "chevron-right"
                },
            subtitle =
                if (bookmark is Bookmark.File && node.noteId == null) stringResource(R.string.note_missing) else null,
            onClick = { node.open(actions) },
            modifier = Modifier.padding(start = (node.depth * INDENT).dp),
        )
    }
}

private const val INDENT = 20
