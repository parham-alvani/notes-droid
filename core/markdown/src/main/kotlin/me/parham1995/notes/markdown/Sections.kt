package me.parham1995.notes.markdown

/**
 * The outline as a tree of headings: which of them have headings under them,
 * and which the outline shows when some are closed.
 *
 * Closing a heading here closes it in the outline only; the note is not
 * folded. A heading is named by `level:text`, the way the Mac names it, so an
 * edit above it does not open it again. Everything is a function of the
 * headings' levels, in order.
 */
object OutlineTree {
    /** How the outline names a heading to keep it closed. */
    fun key(
        level: Int,
        text: String,
    ): String = "$level:$text"

    /** Whether headings come under the heading at [index]: the next is deeper. */
    fun hasChildren(
        levels: List<Int>,
        index: Int,
    ): Boolean = index + 1 < levels.size && levels[index + 1] > levels[index]

    /**
     * The headings shown, in order, when those at the positions in [closed]
     * are closed: the headings under a closed one are left out.
     */
    fun visible(
        levels: List<Int>,
        closed: (Int) -> Boolean,
    ): List<Int> {
        val shown = mutableListOf<Int>()
        var under: Int? = null
        for ((index, level) in levels.withIndex()) {
            val limit = under
            if (limit != null) {
                if (level > limit) continue
                under = null
            }
            shown += index
            if (closed(index) && hasChildren(levels, index)) under = level
        }
        return shown
    }

    /**
     * The heading the outline marks for the heading at [index]: itself, or the
     * closed heading it is under. [visible] is what [visible] gave.
     */
    fun shown(
        index: Int,
        visible: List<Int>,
    ): Int? = visible.lastOrNull { it <= index }

    /** The headings right under the one at [index]: those of the highest level in its section. */
    fun children(
        levels: List<Int>,
        index: Int,
    ): List<Int> {
        if (!hasChildren(levels, index)) return emptyList()
        val below = (index + 1 until levels.size).takeWhile { levels[it] > levels[index] }
        val top = below.minOf { levels[it] }
        return below.filter { levels[it] == top }
    }
}

/**
 * A note's sections, read from its text the way the Mac reads them: ATX
 * headings outside code fences, comments and the front matter. Used to move
 * whole sections, a heading with everything under it, from the phone.
 */
object Sections {
    data class Heading(
        val level: Int,
        val text: String,
        /** Zero-based line in the file. */
        val line: Int,
    )

    private val HEADING = Regex("""^(#{1,6})[ \t]+(.*?)[ \t]*#*[ \t]*$""")
    private val FENCE = Regex("""^\s*(?:>\s*)*(```+|~~~+)""")

    /** The lines of [text], without the empty one after a last line break; and whether there was one. */
    private fun lines(text: String): Pair<List<String>, Boolean> {
        val lines = text.split("\n").toMutableList()
        val newline = lines.size > 1 && lines.last() == ""
        if (newline) lines.removeAt(lines.lastIndex)
        return lines to newline
    }

    fun headings(text: String): List<Heading> = headings(lines(text).first)

    private fun headings(raw: List<String>): List<Heading> {
        val lines = raw.map { it.removeSuffix("\r") }
        val out = mutableListOf<Heading>()
        var fence: String? = null
        var comment = false
        for (index in frontMatterLines(lines) until lines.size) {
            val line = lines[index]
            val match = FENCE.find(line)
            if (match != null) {
                val marker = match.groupValues[1]
                val open = fence
                if (open == null) {
                    fence = marker
                } else if (marker.first() == open.first() && marker.length >= open.length) {
                    fence = null
                }
                continue
            }
            if (fence != null) continue
            val (kept, stillOpen) = stripComments(line, comment)
            comment = stillOpen
            if (kept.startsWith("#")) {
                HEADING.find(kept)?.let { out += Heading(it.groupValues[1].length, it.groupValues[2], index) }
            }
        }
        return out
    }

    private fun frontMatterLines(lines: List<String>): Int {
        if (lines.firstOrNull()?.trim() != "---") return 0
        val end =
            (1 until lines.size).firstOrNull { lines[it].trim().let { line -> line == "---" || line == "..." } }
                ?: return 0
        return end + 1
    }

    /** The line without its `%% … %%` comments, and whether one is still open after it. */
    private fun stripComments(
        line: String,
        open: Boolean,
    ): Pair<String, Boolean> {
        if (!open && "%%" !in line) return line to false
        val out = StringBuilder()
        var inside = open
        var rest = line
        while (true) {
            val at = rest.indexOf("%%")
            if (at < 0) {
                if (!inside) out.append(rest)
                break
            }
            if (!inside) out.append(rest, 0, at)
            inside = !inside
            rest = rest.substring(at + 2)
        }
        return out.toString() to inside
    }

    /**
     * The heading's text as the outline shows it: links and emphasis marks
     * gone, `[[a|b]]` as `b`. It is how a heading picked in the outline is
     * found in the file.
     */
    fun plain(text: String): String =
        text
            .replace(Regex("""!?\[\[([^\]|]*)\|([^\]]*)]]""")) { it.groupValues[2] }
            .replace(
                Regex("""!?\[\[([^\]]*)]]"""),
            ) { it.groupValues[1].substringBefore('#').ifEmpty { it.groupValues[1] } }
            .replace(Regex("""!?\[([^\]]*)]\([^)]*\)""")) { it.groupValues[1] }
            .replace(Regex("""[*_`~=]"""), "")
            .trim()

    /**
     * The position of the [occurrence]-th heading (from 0) of [level] that
     * reads [text] in the outline, or null when the file has no such heading.
     */
    fun find(
        headings: List<Heading>,
        level: Int,
        text: String,
        occurrence: Int,
    ): Int? {
        val want = plain(text)
        return headings.indices
            .filter {
                headings[it].level == level &&
                    plain(
                        headings[it].text,
                    ) == want
            }.getOrNull(occurrence)
    }

    /** The headings of the level of the one at [index] under the same parent, in order, [index] among them. */
    private fun siblings(
        headings: List<Heading>,
        index: Int,
    ): List<Int> {
        val level = headings[index].level
        var first = index
        while (first > 0 && headings[first - 1].level >= level) first--
        var last = index
        while (last + 1 < headings.size && headings[last + 1].level >= level) last++
        return (first..last).filter { headings[it].level == level }
    }

    /**
     * The section of the heading at [index] at [position] among its siblings.
     * The blank lines after each section stay where they are. Null when it is
     * there already.
     */
    fun move(
        text: String,
        index: Int,
        position: Int,
    ): String? {
        val (lines, newline) = lines(text)
        val headings = headings(lines)
        if (index !in headings.indices) return null
        val siblings = siblings(headings, index)
        val from = siblings.indexOf(index)
        if (position !in siblings.indices || position == from) return null
        val starts = siblings.map { headings[it].line }
        val lastLevel = headings[siblings.last()].level
        val stop = headings.drop(siblings.last() + 1).firstOrNull { it.level <= lastLevel }?.line ?: lines.size
        // Each section is its content and the blank lines after it.
        val contents = mutableListOf<List<String>>()
        val gaps = mutableListOf<List<String>>()
        for ((at, start) in starts.withIndex()) {
            val finish = if (at + 1 < starts.size) starts[at + 1] else stop
            var content = finish
            while (content > start + 1 && lines[content - 1].isBlank()) content--
            contents += lines.subList(start, content)
            gaps += lines.subList(content, finish)
        }
        val order = contents.indices.toMutableList()
        order.add(position, order.removeAt(from))
        val out = lines.subList(0, starts[0]).toMutableList()
        for ((slot, section) in order.withIndex()) {
            out += contents[section]
            out += gaps[slot]
        }
        out += lines.subList(stop, lines.size)
        return out.joinToString("\n") + if (newline) "\n" else ""
    }

    /**
     * The text with the headings right under the one at [index] in the order
     * of their names, each with its section, numbers in a name by their value.
     * The text before the first of them stays first. Null when they are in
     * order already or there are fewer than two.
     */
    fun sortChildren(
        text: String,
        index: Int,
    ): String? {
        val start = headings(text)
        if (index !in start.indices) return null
        val names = OutlineTree.children(start.map { it.level }, index).map { start[it].text }
        val sorted = names.indices.sortedWith { a, b -> NATURAL.compare(names[a], names[b]) }
        if (names.size < 2 || sorted == names.indices.toList()) return null
        var out = text
        // Where each section is now, by its first place.
        val places = names.indices.toMutableList()
        for ((position, wanted) in sorted.withIndex()) {
            val at = places.indexOf(wanted)
            if (at == position) continue
            val now = headings(out)
            val child = OutlineTree.children(now.map { it.level }, index)[at]
            out = move(out, child, position) ?: return null
            places.add(position, places.removeAt(at))
        }
        return out
    }

    /** Names as Finder orders them: case aside, and numbers by their value. */
    private val NATURAL =
        Comparator<String> { a, b ->
            val x = a.lowercase()
            val y = b.lowercase()
            var i = 0
            var j = 0
            while (i < x.length && j < y.length) {
                if (x[i].isDigit() && y[j].isDigit()) {
                    val ei = (i until x.length).firstOrNull { !x[it].isDigit() } ?: x.length
                    val ej = (j until y.length).firstOrNull { !y[it].isDigit() } ?: y.length
                    val m = x.substring(i, ei).trimStart('0')
                    val n = y.substring(j, ej).trimStart('0')
                    if (m.length != n.length) return@Comparator m.length - n.length
                    val c = m.compareTo(n)
                    if (c != 0) return@Comparator c
                    i = ei
                    j = ej
                } else {
                    if (x[i] != y[j]) return@Comparator x[i].compareTo(y[j])
                    i++
                    j++
                }
            }
            (x.length - i) - (y.length - j)
        }
}
