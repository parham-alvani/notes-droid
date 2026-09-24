package me.parham1995.notes.obsidian

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One entry in Obsidian's core Bookmarks plugin.
 *
 * Every kind carries the title it was given, which is optional: Obsidian shows
 * the file's or folder's own name, or the query, when there is none, and so
 * does [label].
 */
sealed interface Bookmark {
    val title: String?

    /** What to show for it: its title, or what it points at. */
    val label: String

    /**
     * A note, or a place in one.
     *
     * [heading] is set for a bookmarked heading. A bookmarked block (`#^id`)
     * opens the note, since a block has nowhere of its own to scroll to here.
     */
    data class File(
        val path: String,
        val heading: String? = null,
        override val title: String? = null,
    ) : Bookmark {
        override val label: String
            get() =
                title?.takeIf { it.isNotBlank() }
                    ?: heading?.let { "${fileName(path)} › $it" }
                    ?: fileName(path)
    }

    data class Folder(
        val path: String,
        override val title: String? = null,
    ) : Bookmark {
        override val label: String get() = title?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
    }

    data class Search(
        val query: String,
        override val title: String? = null,
    ) : Bookmark {
        override val label: String get() = title?.takeIf { it.isNotBlank() } ?: query
    }

    data class Url(
        val url: String,
        override val title: String? = null,
    ) : Bookmark {
        override val label: String get() = title?.takeIf { it.isNotBlank() } ?: url
    }

    /** Bookmarks gathered under a name. Groups nest. */
    data class Group(
        override val title: String?,
        val items: List<Bookmark>,
    ) : Bookmark {
        override val label: String get() = title.orEmpty()
    }
}

/** Obsidian's core Bookmarks plugin, read. */
object Bookmarks {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads `.obsidian/bookmarks.json`.
     *
     * Walked as a tree rather than mapped onto declared classes, for the same
     * reason the Iconic plugin's file is: it belongs to Obsidian, which is free
     * to add kinds and fields, and a strict mapping would turn the next one
     * into no bookmarks at all. An entry of a kind not known here is skipped;
     * a group left empty is kept, because it was made on purpose.
     *
     * @throws kotlinx.serialization.SerializationException if the file is not JSON.
     */
    fun parse(text: String): List<Bookmark> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        return items(root["items"] as? JsonArray)
    }

    private fun items(array: JsonArray?): List<Bookmark> =
        array.orEmpty().mapNotNull { (it as? JsonObject)?.let(::item) }

    private fun item(node: JsonObject): Bookmark? {
        val title = node.string("title")
        return when (node.string("type")) {
            "file" -> {
                val path = node.string("path")?.takeIf { it.isNotBlank() } ?: return null
                Bookmark.File(path, heading(node.string("subpath")), title)
            }
            // Older releases wrote a heading as a kind of its own.
            "heading" -> {
                val path = node.string("path")?.takeIf { it.isNotBlank() } ?: return null
                Bookmark.File(path, heading(node.string("subpath")) ?: node.string("heading"), title)
            }
            // The vault root is written as "/", and is kept as the root.
            "folder" -> Bookmark.Folder(node.string("path")?.trim('/') ?: return null, title)
            "search" -> Bookmark.Search(node.string("query")?.takeIf { it.isNotBlank() } ?: return null, title)
            "url" -> Bookmark.Url(node.string("url")?.takeIf { it.isNotBlank() } ?: return null, title)
            "group" -> Bookmark.Group(title, items(node["items"] as? JsonArray))
            else -> null
        }
    }

    /**
     * The heading a subpath names. `#Heading` is one and `#^block` a block;
     * nested headings are written `#Parent#Child`, and the last is the one
     * that was bookmarked.
     */
    private fun heading(subpath: String?): String? {
        if (subpath.isNullOrBlank() || subpath.startsWith("#^")) return null
        return subpath.split('#').lastOrNull { it.isNotBlank() }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** A note's name as Obsidian shows it: the file name without `.md`. */
private fun fileName(path: String): String = path.substringAfterLast('/').removeSuffix(".md")
