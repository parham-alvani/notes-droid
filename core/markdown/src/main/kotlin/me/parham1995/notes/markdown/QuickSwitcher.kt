package me.parham1995.notes.markdown

/**
 * The quick switcher's ranking, as Obsidian's switcher and VS Code's Quick
 * Open work, and as Daftar for macOS ranks its ⌘P: every word of the query,
 * in any order, matches the note's name, one of its aliases or its folders,
 * fuzzily. A word with a `/` in it matches the whole path, so `comp/acme`
 * and `Companies/` find the notes in that folder.
 *
 * The name counts most and the folders least; an exact name, and a name
 * that starts with the query, come first. Once one note holds every word as
 * written or from the start of its words, the notes that only hold the
 * letters spread out are left out: "car insurance" is not "Cards ...
 * Reinsurance".
 */
object QuickSwitcher {
    /** A note to rank, folded once rather than on every keystroke. */
    class Candidate(
        val id: Long,
        val path: String,
        title: String,
        aliases: List<String> = emptyList(),
    ) {
        internal val title = SearchText.foldName(title)
        internal val aliases = aliases.map(SearchText::foldName)
        internal val folders = SearchText.foldName(path.substringBeforeLast('/', ""))
        internal val full = if (folders.isEmpty()) this.title else "$folders/${this.title}"
    }

    /** The ids of the notes that match [query], best first. */
    fun rank(
        query: String,
        candidates: List<Candidate>,
        limit: Int = 50,
    ): List<Long> {
        val needle = SearchText.foldName(query.trim())
        val words = needle.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        class Scored(
            val candidate: Candidate,
            val score: Int,
            val match: Int,
            val close: Boolean,
        )
        var scored =
            candidates.mapNotNull { candidate ->
                val (match, close) = score(words, candidate) ?: return@mapNotNull null
                var score = match
                when {
                    candidate.title == needle || needle in candidate.aliases || candidate.full == needle ->
                        score +=
                            EXACT
                    candidate.title.startsWith(needle) -> score += PREFIX
                    needle in candidate.title -> score += INSIDE
                }
                Scored(candidate, score, match, close)
            }
        if (scored.any { it.close }) scored = scored.filter { it.close }
        val best = scored.maxOfOrNull { it.match } ?: return emptyList()
        // Letters spread over a path match nearly anything: once something
        // matches well, what matches under half as well is noise.
        return scored
            .filter { it.match == best || it.match * 2 >= best }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { it.candidate.path.length }
                    .thenBy { it.candidate.path },
            ).take(limit)
            .map { it.candidate.id }
    }

    /** The sum of each word's best match, and whether every word matched closely; null when one matches nowhere. */
    private fun score(
        words: List<String>,
        candidate: Candidate,
    ): Pair<Int, Boolean>? {
        var total = 0
        var close = true
        for (word in words) {
            var best: Int? = null
            var near = false

            fun consider(
                text: String,
                weight: Int,
            ) {
                val match = Fuzzy.match(word, text) ?: return
                near = near || isClose(word, text, match.positions)
                var value = match.score * 4 + weight
                value +=
                    when {
                        text.startsWith(word) -> 40
                        " $word" in text || "-$word" in text || "/$word" in text -> 25
                        word in text -> 12
                        else -> 0
                    }
                if (match.positions.last() - match.positions.first() + 1 > word.length * 3) value -= 30
                if (best == null || value > best!!) best = value
            }
            if ('/' in word) {
                consider(candidate.full, 30)
            } else {
                consider(candidate.title, 60)
                candidate.aliases.forEach { consider(it, 45) }
                consider(candidate.folders, 0)
            }
            total += best ?: return null
            close = close && near
        }
        return total to close
    }

    /** The word as written, or its letters from the start of a word and not spread far. */
    internal fun isClose(
        word: String,
        text: String,
        positions: List<Int>,
    ): Boolean {
        if (word in text) return true
        val first = positions.first()
        val start = first == 0 || text[first - 1] in BOUNDARIES
        return start && positions.last() - first + 1 <= word.length * 2
    }

    private const val EXACT = 1000
    private const val PREFIX = 300
    private const val INSIDE = 120
    private const val BOUNDARIES = " -_/.("
    private val WHITESPACE = Regex("""\s+""")
}

/**
 * A fuzzy match, as a command palette does it: every letter of the query in
 * order, anywhere in the text. A letter at the start of a word, and letters
 * in a row, score more; a gap scores less. Both texts are folded already.
 */
internal object Fuzzy {
    class Match(
        val score: Int,
        val positions: List<Int>,
    )

    fun match(
        query: String,
        text: String,
    ): Match? {
        val needle = query.filterNot(Char::isWhitespace)
        val n = text.length
        val m = needle.length
        if (m == 0 || m > n || !mayMatch(needle, text)) return null
        val none = Int.MIN_VALUE / 2
        val best = Array(m) { IntArray(n) { none } }
        val from = Array(m) { IntArray(n) { -1 } }
        for (i in 0 until m) {
            for (j in i until n) {
                if (text[j] != needle[i]) continue
                val own =
                    1 +
                        if (j == 0) {
                            8
                        } else if (isBoundary(text, j)) {
                            6
                        } else {
                            0
                        }
                if (i == 0) {
                    best[i][j] = own
                    continue
                }
                for (k in (i - 1) until j) {
                    if (best[i - 1][k] <= none) continue
                    val step = if (k == j - 1) 5 else -minOf(4, j - k - 1)
                    val score = best[i - 1][k] + own + step
                    if (score > best[i][j]) {
                        best[i][j] = score
                        from[i][j] = k
                    }
                }
            }
        }
        val last = (0 until n).maxByOrNull { best[m - 1][it] } ?: return null
        if (best[m - 1][last] <= none) return null
        val positions = ArrayDeque<Int>().apply { add(last) }
        for (i in m - 1 downTo 1) positions.addFirst(from[i][positions.first()])
        // Shorter texts win a tie.
        return Match(best[m - 1][last] - n / 12, positions.toList())
    }

    /** Every letter of [word] in [text] in order: the quick test that spares the full match. */
    private fun mayMatch(
        word: String,
        text: String,
    ): Boolean {
        var at = 0
        for (character in text) {
            if (character == word[at] && ++at == word.length) return true
        }
        return false
    }

    private fun isBoundary(
        text: String,
        at: Int,
    ): Boolean {
        val before = text[at - 1]
        return before.isWhitespace() || before in "-_/.:›("
    }
}
