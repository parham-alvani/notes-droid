package me.parham1995.notes.feature.note

/** One remembered note: which vault it is in, and where in that vault. */
data class TabRef(
    val vaultId: Long,
    val path: String,
)

/** A tab as it is written down: the trail it has been through, and where in it. */
data class StoredTab(
    val trail: List<TabRef>,
    val index: Int,
)

data class StoredTabs(
    val tabs: List<StoredTab>,
    val active: Int,
)

/**
 * Tabs, written down so they survive the app being closed.
 *
 * **By path, not by id.** A note's id is a row number a reindex hands out
 * again -- and reindexing happens whenever the indexer learns something new,
 * twice in the last week -- so a remembered id can come back pointing at a
 * different note, or at none. A vault and a path are what the note is.
 *
 * Fields are separated by the ASCII separator characters, which a file path
 * cannot contain. Paths in this vault hold spaces, `#`, `&` and Persian, so
 * any printable separator is one that eventually turns up inside a name.
 */
internal object TabsCodec {
    private const val FIELD = '\u001F'
    private const val RECORD = '\u001E'
    private const val GROUP = '\u001D'

    fun encode(stored: StoredTabs): String =
        buildString {
            append(stored.active)
            stored.tabs.forEach { tab ->
                append(GROUP).append(tab.index)
                tab.trail.forEach { ref ->
                    append(RECORD).append(ref.vaultId).append(FIELD).append(ref.path)
                }
            }
        }

    /** Null when there is nothing written down, or what is written makes no sense. */
    fun decode(text: String?): StoredTabs? {
        if (text.isNullOrEmpty()) return null
        val groups = text.split(GROUP)
        val active = groups.firstOrNull()?.toIntOrNull() ?: return null
        val tabs =
            groups.drop(1).mapNotNull { group ->
                val records = group.split(RECORD)
                val index = records.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
                val trail =
                    records.drop(1).mapNotNull { record ->
                        val field = record.indexOf(FIELD)
                        if (field <= 0) return@mapNotNull null
                        val vault = record.substring(0, field).toLongOrNull() ?: return@mapNotNull null
                        val path = record.substring(field + 1)
                        if (path.isEmpty()) null else TabRef(vault, path)
                    }
                // A tab with nothing in it is not a tab, and an index outside
                // its own trail is a tab that would show nothing.
                if (trail.isEmpty()) null else StoredTab(trail, index.coerceIn(0, trail.lastIndex))
            }
        if (tabs.isEmpty()) return null
        return StoredTabs(tabs, active.coerceIn(0, tabs.lastIndex))
    }
}
