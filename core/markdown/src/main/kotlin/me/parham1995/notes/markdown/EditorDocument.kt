package me.parham1995.notes.markdown

/**
 * A note's text as the editor's lines, kept from one key press to the next.
 *
 * [update] finds what the new text shares with the last one at its start and
 * at its end, and reads only the lines between with [EditorMarkup]. It reads
 * on past them only while a line starts in another block than it did: a
 * fence typed above a paragraph makes that paragraph code. Every other line
 * is the same [EditorLine] as before, so what was drawn for it is kept.
 */
class EditorDocument {
    private class Row(
        val text: String,
        val before: EditorState,
        val read: EditorRead,
    )

    private var rows = ArrayList<Row>()
    private var starts = IntArray(0)

    /** The text the lines were read from. */
    var text: String = ""
        private set

    /** How many lines [update] read the last time, for a test of the cost. */
    var lastRead: Int = 0
        private set

    val size: Int get() = rows.size

    fun line(index: Int): EditorLine = rows[index].read.line

    /** Where line [index] starts in [text]. */
    fun start(index: Int): Int = starts[index]

    /** The line that holds [offset]: the one before a line break at that place. */
    fun lineAt(offset: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (starts[middle] <= offset) low = middle else high = middle - 1
        }
        return low
    }

    /**
     * Where the heading that is number [ordinal] of the note, from 0, ends:
     * the place the editor opens at for a note being read under it. Null when
     * the note has no such heading.
     */
    fun headingEnd(ordinal: Int): Int? {
        var seen = 0
        for (index in rows.indices) {
            val line = rows[index].read.line
            if (line.kind != EditorLineKind.HEADING) continue
            if (seen == ordinal) return starts[index] + line.length
            seen++
        }
        return null
    }

    /** Reads [next], which becomes [text]. */
    fun update(next: String) {
        if (rows.isNotEmpty() && next == text) {
            lastRead = 0
            return
        }
        if (rows.isEmpty()) {
            rows = read(next.split('\n'), EditorState.START)
            lastRead = rows.size
        } else {
            splice(next)
        }
        text = next
        starts = IntArray(rows.size)
        var offset = 0
        for (i in rows.indices) {
            starts[i] = offset
            offset += rows[i].text.length + 1
        }
    }

    private fun splice(next: String) {
        val old = text
        val shortest = minOf(old.length, next.length)
        var prefix = 0
        while (prefix < shortest && old[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < shortest - prefix && old[old.length - 1 - suffix] == next[next.length - 1 - suffix]) suffix++

        // The lines of the old text the change touches, whole.
        val first = lineAt(prefix)
        val last = lineAt(old.length - suffix)
        val tail = old.length - (starts[last] + rows[last].text.length)
        val middle = next.substring(starts[first], next.length - tail).split('\n')

        val fresh = read(middle, rows[first].before)
        var state = fresh.last().read.after
        var read = fresh.size
        // The lines after: read again while one starts in another block than before.
        var kept = last + 1
        while (kept < rows.size && rows[kept].before != state) {
            val row = Row(rows[kept].text, state, EditorMarkup.line(rows[kept].text, state))
            fresh += row
            state = row.read.after
            kept++
            read++
        }
        val out = ArrayList<Row>(first + fresh.size + rows.size - kept)
        out.addAll(rows.subList(0, first))
        out.addAll(fresh)
        out.addAll(rows.subList(kept, rows.size))
        rows = out
        lastRead = read
    }

    private fun read(
        lines: List<String>,
        from: EditorState,
    ): ArrayList<Row> {
        val out = ArrayList<Row>(lines.size)
        var state = from
        for (line in lines) {
            val row = Row(line, state, EditorMarkup.line(line, state))
            out += row
            state = row.read.after
        }
        return out
    }
}
