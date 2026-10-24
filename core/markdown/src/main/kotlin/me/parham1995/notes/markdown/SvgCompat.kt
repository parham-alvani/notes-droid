package me.parham1995.notes.markdown

/**
 * Rewrites an SVG into the SVG 1.1 that AndroidSVG -- the parser under Coil's
 * SVG decoder -- can read, keeping what it was meant to look like.
 *
 * Two things in hand-built figures break it, and neither breaks a browser or
 * Obsidian, so a vault full of them looks fine everywhere but here:
 *
 * - `orient="auto-start-reverse"` on a `<marker>` is SVG 2. AndroidSVG reads
 *   anything but `auto` as a number, throws, and the whole file fails: the
 *   figure was a caption with nothing above it. The marker becomes `auto`, and
 *   a copy turned half a circle takes over every `marker-start` that named
 *   it, which is what "start reverse" means.
 * - `@media (prefers-color-scheme: dark)` is not a media type, and AndroidSVG's
 *   CSS parser gives up at the first one -- dropping it and every rule after
 *   it, so a figure lost its box fills, its arrows and its monospace. Each
 *   colour-scheme block is settled here instead: its rules kept on a dark page
 *   and dropped on a light one. A block asking about anything else is dropped,
 *   since the alternative is losing the rest of the sheet.
 *
 * Text in, text out, and a file with neither is returned as it came.
 */
object SvgCompat {
    fun rewrite(
        svg: String,
        dark: Boolean,
    ): String {
        val markers = if (svg.contains(REVERSE)) reverseMarkers(svg) else svg
        return if (markers.contains("@media")) settleMedia(markers, dark) else markers
    }

    private fun reverseMarkers(svg: String): String {
        val reversed = mutableMapOf<String, String>()
        val clones = StringBuilder()
        val taken = ID.findAll(svg).map { it.groupValues[2] }.toMutableSet()
        val fixed =
            MARKER.replace(svg) { match ->
                val element = match.value
                if (!element.contains(REVERSE)) return@replace element
                val forward = element.replace(REVERSE, "\"auto\"")
                val id = ID.find(forward)?.groupValues?.get(2) ?: return@replace forward
                val cloneId = unique("$id-start-reverse", taken)
                reversed[id] = cloneId
                clones.append(turned(forward, cloneId))
                forward
            }
        if (reversed.isEmpty()) return fixed
        // The copies go straight after the last marker, which is inside the
        // same <defs> the originals were in.
        val last =
            MARKER
                .findAll(fixed)
                .last()
                .range.last + 1
        val withClones = fixed.substring(0, last) + clones + fixed.substring(last)
        return MARKER_START.replace(withClones) { match ->
            val clone = reversed[match.groupValues[2]] ?: return@replace match.value
            match.value.replace("#${match.groupValues[2]}", "#$clone")
        }
    }

    /**
     * [marker] under [cloneId], turned half a circle.
     *
     * About the middle of its box, not about its reference point: a marker is
     * clipped to its box, and an arrowhead turned about its own tip lands
     * almost wholly outside it -- the first try drew every start arrow as
     * nothing. The reference point is mirrored through the same middle, so it
     * stays on the tip.
     */
    private fun turned(
        marker: String,
        cloneId: String,
    ): String {
        val open = marker.substring(0, marker.indexOf('>') + 1)
        val renamed = ID.replace(open) { "${it.groupValues[1]}\"$cloneId\"" }
        if (open.endsWith("/>")) return renamed
        val box =
            attribute(open, "viewBox")
                ?.split(SEPARATORS)
                ?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 4 }
        // With no viewBox the box is the marker's own size, 3 by 3 by default.
        val cx = box?.let { it[0] + it[2] / 2 } ?: ((attribute(open, "markerWidth")?.toFloatOrNull() ?: 3f) / 2)
        val cy = box?.let { it[1] + it[3] / 2 } ?: ((attribute(open, "markerHeight")?.toFloatOrNull() ?: 3f) / 2)
        val refX = attribute(open, "refX")?.toFloatOrNull() ?: 0f
        val refY = attribute(open, "refY")?.toFloatOrNull() ?: 0f
        val mirrored =
            renamed
                .withAttribute("refX", number(2 * cx - refX))
                .withAttribute("refY", number(2 * cy - refY))
        val body = marker.substring(open.length, marker.lastIndexOf("</marker>"))
        return "$mirrored<g transform=\"rotate(180 ${number(cx)} ${number(cy)})\">$body</g></marker>"
    }

    private fun attribute(
        tag: String,
        name: String,
    ): String? = Regex("""\b$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    /** This opening tag with [name] set to [value], replaced or added. */
    private fun String.withAttribute(
        name: String,
        value: String,
    ): String {
        val existing = Regex("""\b$name="[^"]*"""")
        return if (existing.containsMatchIn(this)) {
            existing.replace(this, "$name=\"$value\"")
        } else {
            dropLast(1) + " $name=\"$value\">"
        }
    }

    private fun number(value: Float): String = if (value % 1f == 0f) value.toLong().toString() else value.toString()

    private fun unique(
        wanted: String,
        taken: MutableSet<String>,
    ): String {
        var candidate = wanted
        var n = 2
        while (!taken.add(candidate)) candidate = "$wanted-${n++}"
        return candidate
    }

    /** Every `@media` block, settled or removed; braces are counted by hand. */
    private fun settleMedia(
        svg: String,
        dark: Boolean,
    ): String {
        val out = StringBuilder()
        var from = 0
        while (true) {
            val at = svg.indexOf("@media", from)
            if (at < 0) break
            val open = svg.indexOf('{', at)
            if (open < 0) break
            val close = closing(svg, open) ?: break
            out.append(svg, from, at)
            val prelude = svg.substring(at + "@media".length, open)
            when (matches(prelude, dark)) {
                true -> out.append(svg, open + 1, close)
                false -> Unit
                null -> out.append(svg, at, close + 1)
            }
            from = close + 1
        }
        return out.append(svg, from, svg.length).toString()
    }

    private fun closing(
        text: String,
        open: Int,
    ): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return null
    }

    /**
     * Whether a media query list holds on a phone page that is [dark] or not,
     * or null for a plain list of media types, which AndroidSVG reads itself.
     */
    private fun matches(
        prelude: String,
        dark: Boolean,
    ): Boolean? {
        val queries = prelude.lowercase().split(',').map { it.trim() }
        if (queries.none { it.contains('(') }) return null
        return queries.any { query -> holds(query, dark) }
    }

    private fun holds(
        query: String,
        dark: Boolean,
    ): Boolean {
        val features = FEATURE.findAll(query).map { it.groupValues[1].replace(" ", "") }.toList()
        val words = FEATURE.replace(query, " ").split(' ').filter { it.isNotBlank() }
        val types = words.filter { it != "and" && it != "only" }
        if (types.any { it != "all" && it != "screen" }) return false
        return features.all { feature ->
            when (feature) {
                "prefers-color-scheme:dark" -> dark
                "prefers-color-scheme:light" -> !dark
                else -> false
            }
        }
    }

    private const val REVERSE = "\"auto-start-reverse\""
    private val MARKER = Regex("""<marker\b[^>]*?(?:/>|>.*?</marker>)""", RegexOption.DOT_MATCHES_ALL)
    private val ID = Regex("""(\bid=)"([^"]*)"""")
    private val SEPARATORS = Regex("""[\s,]+""")
    private val MARKER_START = Regex("""marker-start\s*[:=]\s*"?\s*(url\(#([^)\s"]+)\))""")
    private val FEATURE = Regex("""\(([^)]*)\)""")
}
