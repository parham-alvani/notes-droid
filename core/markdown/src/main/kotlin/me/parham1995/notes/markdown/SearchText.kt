package me.parham1995.notes.markdown

/**
 * Text as search compares it: Arabic and Persian letter forms made one.
 *
 * A Persian keyboard types ی (U+06CC) and ک (U+06A9); text pasted from an
 * Arabic source, or typed years ago on an Arabic layout, carries ي (U+064A)
 * and ك (U+0643). They look the same on screen and are different characters to
 * FTS5, so a query typed today silently missed every note written the other
 * way. Search applies this to both sides -- what is indexed and what is typed
 * -- so the two meet.
 *
 * What it does, and why each is safe for Persian:
 *
 * - ي and ى become ی, ك becomes ک. Persian has one yeh and one kaf; the
 *   Arabic forms are the same letter from a different code page.
 * - ة and ۀ become ه. In Persian ة only appears word-finally in Arabic
 *   loanwords that are written with ه just as often (علاقة / علاقه), and ۀ is
 *   ه with a hamza, which is dropped below anyway. No Persian word is told
 *   apart from another by it.
 * - Arabic-Indic and Persian digits become ASCII, so ۱۴۰۴ finds 1404 and back.
 * - Tatweel, the harakat (U+064B-U+065F) and the superscript alef are removed.
 *   They are decoration or vowel marks nobody types in a query, and FTS5's
 *   tokenizer treats a combining mark as a word break -- so a note with one
 *   vowel mark in a word could not be found by that word at all.
 * - ZWNJ is removed. می‌خواهم and میخواهم are the same word written with and
 *   without the half-space, and FTS5 reads the half-space as a break.
 * - The three private-use characters [SNIPPET_MARKERS] are removed, so the
 *   markers search puts around a match can never be mistaken for text.
 *
 * Everything else is left alone, English included -- the alef variants (آ, أ,
 * إ) are not folded, because آ is a letter of its own in Persian.
 *
 * It is one pass over the string and returns the same instance when nothing
 * needed changing, which is every note that has no Arabic script in it.
 */
object SearchText {
    const val SNIPPET_OPEN = '\uE000'
    const val SNIPPET_CLOSE = '\uE001'
    const val SNIPPET_ELLIPSIS = '\uE002'

    /** The characters search wraps a match in; see [restoreSnippet]. */
    val SNIPPET_MARKERS = charArrayOf(SNIPPET_OPEN, SNIPPET_CLOSE, SNIPPET_ELLIPSIS)

    private const val KEEP = -1
    private const val DROP = -2

    fun normalize(text: String): String {
        val first = text.indexOfFirst { replacement(it) != KEEP }
        if (first < 0) return text
        val out = StringBuilder(text.length)
        out.append(text, 0, first)
        for (i in first until text.length) {
            val c = text[i]
            when (val r = replacement(c)) {
                KEEP -> out.append(c)
                DROP -> Unit
                else -> out.append(r.toChar())
            }
        }
        return out.toString()
    }

    /** A name as the quick switcher compares it: [Slugs.fold], then [normalize]. */
    fun foldName(text: String): String = normalize(Slugs.fold(text))

    /**
     * [normalize], remembering where every character came from.
     *
     * The index holds normalized text, so what FTS5 hands back is normalized
     * too; this is how an excerpt of it is turned back into the words the note
     * actually says.
     */
    fun mapped(text: String): Mapped {
        val out = StringBuilder(text.length)
        val origin = IntArray(text.length + 1)
        text.forEachIndexed { i, c ->
            when (val r = replacement(c)) {
                KEEP -> {
                    origin[out.length] = i
                    out.append(c)
                }
                DROP -> Unit
                else -> {
                    origin[out.length] = i
                    out.append(r.toChar())
                }
            }
        }
        origin[out.length] = text.length
        return Mapped(out.toString(), origin.copyOf(out.length + 1))
    }

    /** Normalized [text], and for each of its characters the index it had in the original. */
    class Mapped internal constructor(
        val text: String,
        private val origin: IntArray,
    ) {
        /**
         * Where normalized position [index] starts in the original; the
         * original's length for the position one past the end.
         */
        fun originOf(index: Int): Int = origin[index]
    }

    /**
     * An FTS5 `snippet()` of normalized text, shown in the note's own words.
     *
     * [snippet] was produced with [SNIPPET_OPEN], [SNIPPET_CLOSE] and
     * [SNIPPET_ELLIPSIS] as its markers, from a column holding
     * `normalize(original)`. The excerpt is found again in the normalized
     * text, carried back to the same span of [original], and returned with the
     * markers search has always shown: `[` `]` around a match, `...` where the
     * excerpt was cut. A removed character inside a match -- the ZWNJ of
     * می‌خواهم -- lands inside the highlight, and a vowel mark after the last
     * letter stays with it.
     *
     * [original] is null when normalizing changed nothing, and then the
     * snippet already is the original.
     */
    fun restoreSnippet(
        snippet: String,
        original: String?,
    ): String {
        val leading = snippet.startsWith(SNIPPET_ELLIPSIS)
        val trailing = snippet.length > 1 && snippet.endsWith(SNIPPET_ELLIPSIS)
        val fragment = StringBuilder(snippet.length)
        // Positions in the fragment where a highlight opens or closes.
        val marks = mutableListOf<Pair<Int, Char>>()
        snippet.forEach { c ->
            when (c) {
                SNIPPET_OPEN -> marks += fragment.length to '['
                SNIPPET_CLOSE -> marks += fragment.length to ']'
                SNIPPET_ELLIPSIS -> Unit
                else -> fragment.append(c)
            }
        }
        val prefix = if (leading) ELLIPSIS else ""
        val suffix = if (trailing) ELLIPSIS else ""

        val mapped = original?.let(::mapped)
        val start = mapped?.text?.indexOf(fragment.toString()) ?: -1
        if (mapped == null || start < 0) {
            // Nothing to carry back, or nowhere to carry it: the normalized
            // excerpt, marked up the same way, is still the right words.
            val out = StringBuilder(fragment)
            marks.asReversed().forEach { (at, mark) -> out.insert(at, mark) }
            return prefix + out + suffix
        }
        val out = StringBuilder()
        var from = mapped.originOf(start)
        marks.forEach { (at, mark) ->
            val to = mapped.originOf(start + at)
            out.append(original, from, to).append(mark)
            from = to
        }
        out.append(original, from, mapped.originOf(start + fragment.length))
        return prefix + out + suffix
    }

    private const val ELLIPSIS = "..."

    private fun replacement(c: Char): Int =
        when (c) {
            '\u064A', '\u0649' -> '\u06CC'.code
            '\u0643' -> '\u06A9'.code
            '\u0629', '\u06C0' -> '\u0647'.code
            in '\u0660'..'\u0669' -> '0'.code + (c - '\u0660')
            in '\u06F0'..'\u06F9' -> '0'.code + (c - '\u06F0')
            '\u0640', '\u0670', '\u200C' -> DROP
            in '\u064B'..'\u065F' -> DROP
            SNIPPET_OPEN, SNIPPET_CLOSE, SNIPPET_ELLIPSIS -> DROP
            else -> KEEP
        }
}
