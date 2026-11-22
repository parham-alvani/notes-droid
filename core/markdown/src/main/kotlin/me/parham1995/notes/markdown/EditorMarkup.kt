package me.parham1995.notes.markdown

import java.time.LocalDate

/** What a range of a line is, for the editor to colour. */
enum class EditorStyle {
    HEADING,
    STRONG,
    EMPHASIS,
    STRIKE,
    CODE,
    HIGHLIGHT,
    LINK,
    TAG,
    MATH,
    COMMENT,
    LIST_MARKER,
    RULE,
    CALLOUT_TITLE,
}

/** Characters [start] until [end] of a line. */
data class EditorRange(
    val start: Int,
    val end: Int,
)

/** A range of a line and what it is. [color] is a highlight's, null for the plain one. */
data class EditorSpan(
    val start: Int,
    val end: Int,
    val style: EditorStyle,
    val color: HighlightColor? = null,
)

/**
 * One element with marks: `**bold**`, `==marked==`, a code span, a link.
 * It runs from before its first mark to after its last, and its [marks] are
 * shown only while the caret is in that range, both ends counted.
 */
data class EditorElement(
    val start: Int,
    val end: Int,
    val marks: List<EditorRange>,
)

/** Which of a task's dates a chip is. */
enum class EditorChipKind { CREATED, SCHEDULED, START, DUE, DONE, CANCELLED, REPEAT }

/**
 * A date of a task, drawn as a chip: the emoji and the day as written, from
 * [start] until [end] of the line. [day] is null for a repeat, which has a
 * rule and no day.
 */
data class EditorChip(
    val start: Int,
    val end: Int,
    val kind: EditorChipKind,
    val day: LocalDate? = null,
)

/**
 * A task's line. [box] is what the drawn box takes the place of: the marker,
 * its space and the brackets (`- [ ]`), or the brackets alone after a number.
 * [text] is what a closed task strikes: the line's words, with the chips and
 * the reason left out.
 */
class EditorTask(
    val box: EditorRange,
    val status: Char,
    val chips: List<EditorChip> = emptyList(),
    val text: List<EditorRange> = emptyList(),
)

/** What kind of line it is. [PLAIN] holds no Markdown: front matter, `$$` math, a `%%` comment. */
enum class EditorLineKind { TEXT, HEADING, FENCE, CODE, PLAIN }

/** Whether a line is in a `>` run, and which kind of run. */
enum class EditorQuote { NONE, QUOTE, CALLOUT }

/**
 * The first line of a callout. [title] is what the line says after `[!kind]`,
 * or the kind's name with a capital when it says nothing ([titled] false):
 * that name is drawn only, never written.
 */
data class EditorCallout(
    val kind: CalloutKind,
    val title: String,
    val titled: Boolean,
)

/**
 * One line of a note as the editor draws it: a pure reading of its text and
 * of the block it is in. Offsets are in the line.
 *
 * [lineMarks] are the marks shown on the caret's line only: a heading's `#`,
 * the `>` of a quote or a callout, a callout's `[!kind]`.
 */
class EditorLine(
    val length: Int,
    val kind: EditorLineKind,
    val level: Int = 0,
    val quote: EditorQuote = EditorQuote.NONE,
    val callout: EditorCallout? = null,
    val rtl: Boolean = false,
    val spans: List<EditorSpan> = emptyList(),
    val elements: List<EditorElement> = emptyList(),
    val lineMarks: List<EditorRange> = emptyList(),
    /** The line is a task: its box and its date chips. */
    val task: EditorTask? = null,
) {
    /** Whatever drew this line keeps what it made here, so a line that did not change is not made again. */
    var drawn: Any? = null

    /** Every mark of the line, hidden or not. */
    val marks: List<EditorRange> get() = lineMarks + elements.flatMap { it.marks }

    /**
     * The marks drawn at no width. [selection] is the selection in this line's
     * offsets when it touches the line, else null. With it, the line's own
     * marks show, and so do those of each element the selection touches.
     */
    fun hidden(selection: EditorRange?): List<EditorRange> {
        if (selection == null) return marks
        return elements
            .filterNot { selection.end >= it.start && selection.start <= it.end }
            .flatMap { it.marks }
    }
}

/**
 * The block a line starts in. A line's look is a function of its text and of
 * this, so a key press reads its own line again and no other, unless it
 * opened or closed a block.
 */
data class EditorState(
    val block: Block = Block.NONE,
    /** The fence that opened the code block: its character and how many. */
    val fence: String = "",
    /** The code block is inside a `>` run, and ends with it. */
    val fenceQuoted: Boolean = false,
    val quote: EditorQuote = EditorQuote.NONE,
    /** Nothing but the note's first line has this: a `---` there opens the front matter. */
    val first: Boolean = false,
) {
    enum class Block { NONE, FRONT_MATTER, FENCE, MATH, COMMENT }

    companion object {
        /** The state before a note's first line. */
        val START = EditorState(first = true)
    }
}

/** A line read, and the state the next line starts in. */
class EditorRead(
    val line: EditorLine,
    val after: EditorState,
)

/**
 * How the editor reads one line of Markdown (`look.md` in notes-linux's
 * `docs/specs/`, "Live preview" and "Android: the editor").
 *
 * It is not the reader's parser. The reader parses a whole note into blocks;
 * this reads a line at a time, so typing costs one line. The price is that a
 * mark has to close on the line it opened on.
 */
object EditorMarkup {
    private val QUOTE_PREFIX = Regex("""^ {0,3}>(?: ?>)* ?""")
    private val FENCE_OPEN = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    private val HEADING = Regex("""^( {0,3})(#{1,6})(?:[ \t]+|$)""")
    private val RULE = Regex("""^ {0,3}([-*_])(?: *\1){2,} *$""")
    private val LIST = Regex("""^([ \t]*)([-*+]|\d{1,9}[.)])(?: +|$)""")
    private val CALLOUT = Regex("""^\[!([A-Za-z_-]+)\]([+-]?)[ \t]*(.*)$""")

    /** A task: an indent, a marker, one space, one character in brackets, one space. */
    private val TASK = Regex("""^[ \t]*(?:([-*+])|\d{1,9}[.)]) (\[([^\]])\]) """)
    private val REASON = Regex("""\[\s*reason\s*::[^\]]*\]""", RegexOption.IGNORE_CASE)
    private val BLOCK_ID = Regex("""\s\^[A-Za-z0-9-]+\s*$""")
    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    private const val DAY_LENGTH = 10
    private const val REPEAT = "\uD83D\uDD01"
    private val DATES =
        listOf(
            "\u2795" to EditorChipKind.CREATED,
            "\u23F3" to EditorChipKind.SCHEDULED,
            "\uD83D\uDEEB" to EditorChipKind.START,
            "\uD83D\uDCC5" to EditorChipKind.DUE,
            "\u2705" to EditorChipKind.DONE,
            "\u274C" to EditorChipKind.CANCELLED,
        )
    private val PRIORITIES = listOf("\uD83D\uDD3A", "\u23EB", "\uD83D\uDD3C", "\uD83D\uDD3D", "\u23EC")

    /** No emoji of a task is below this, so most of a line is passed over at a glance. */
    private const val FIRST_SYMBOL = '\u2000'
    private val TAG = Regex("""#[\p{L}\p{N}_/-]+""")
    private val URL = Regex("""https?://[^\s<>)\]]+""")
    private const val VARIATION_SELECTOR = '\uFE0F'
    private const val MAX_INDENT = 3

    /** Reads [text], one line with no line break in it, starting in [before]. */
    fun line(
        text: String,
        before: EditorState,
    ): EditorRead {
        val rtl = TextDirection.of(text) == MdDirection.RTL
        val prefix = QUOTE_PREFIX.find(text)?.value?.length ?: 0
        val quoted = prefix > 0
        val state = before.copy(first = false)

        return when {
            before.block == EditorState.Block.FRONT_MATTER -> {
                val closes = text.trimEnd().let { it == "---" || it == "..." }
                plain(text, rtl, if (closes) EditorState() else state)
            }
            before.first && text.trimEnd() == "---" ->
                plain(text, rtl, EditorState(block = EditorState.Block.FRONT_MATTER))
            before.block == EditorState.Block.MATH && !quoted ->
                plain(text, rtl, if (text.trim() == "$$") EditorState() else state)
            before.block == EditorState.Block.COMMENT && !quoted ->
                plain(text, rtl, if ("%%" in text) EditorState() else state)
            // A code block in a quote ends where the quote does.
            before.block == EditorState.Block.FENCE && (quoted || !before.fenceQuoted) ->
                code(text, if (before.fenceQuoted) prefix else 0, rtl, state)
            else -> markdown(text, prefix, rtl, state.copy(block = EditorState.Block.NONE, fence = ""))
        }
    }

    private fun plain(
        text: String,
        rtl: Boolean,
        after: EditorState,
    ) = EditorRead(
        EditorLine(
            length = text.length,
            kind = EditorLineKind.PLAIN,
            rtl = rtl,
            spans = listOf(EditorSpan(0, text.length, EditorStyle.COMMENT)),
        ),
        after,
    )

    private fun code(
        text: String,
        prefix: Int,
        rtl: Boolean,
        state: EditorState,
    ): EditorRead {
        val body = text.substring(prefix)
        val mark = state.fence.first()
        val fence = body.trimStart(' ')
        val run = fence.takeWhile { it == mark }.length
        val closes = run >= state.fence.length && fence.drop(run).isBlank() && body.length - fence.length <= MAX_INDENT
        val marks = if (prefix > 0) listOf(EditorRange(0, prefix)) else emptyList()
        return EditorRead(
            EditorLine(
                length = text.length,
                kind = if (closes) EditorLineKind.FENCE else EditorLineKind.CODE,
                quote = state.quote,
                rtl = rtl,
                spans = listOf(EditorSpan(prefix, text.length, EditorStyle.CODE)),
                lineMarks = marks,
            ),
            if (closes) state.copy(block = EditorState.Block.NONE, fence = "", fenceQuoted = false) else state,
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun markdown(
        text: String,
        prefix: Int,
        rtl: Boolean,
        state: EditorState,
    ): EditorRead {
        val body = text.substring(prefix)
        val spans = ArrayList<EditorSpan>()
        val elements = ArrayList<EditorElement>()
        val lineMarks = ArrayList<EditorRange>()
        if (prefix > 0) lineMarks += EditorRange(0, prefix)

        // Which run of `>` lines this is: a callout is one from its first line on.
        val header = if (prefix > 0 && state.quote == EditorQuote.NONE) CALLOUT.find(body) else null
        val quote =
            when {
                prefix == 0 -> EditorQuote.NONE
                header != null -> EditorQuote.CALLOUT
                state.quote == EditorQuote.NONE -> EditorQuote.QUOTE
                else -> state.quote
            }
        val after = state.copy(quote = quote, fenceQuoted = false)

        fun read(
            kind: EditorLineKind = EditorLineKind.TEXT,
            level: Int = 0,
            callout: EditorCallout? = null,
            next: EditorState = after,
            task: EditorTask? = null,
            words: Int = task?.box?.end ?: 0,
        ) = EditorRead(
            // A task reads as its words do, and a callout as its title does:
            // the `x` in a done box and the kind in `[!note]` are no words.
            EditorLine(
                text.length,
                kind,
                level,
                quote,
                callout,
                if (words == 0) rtl else TextDirection.of(text.substring(words)) == MdDirection.RTL,
                spans,
                elements,
                lineMarks,
                task,
            ),
            next,
        )

        if (header != null) {
            val title = header.groupValues[3].trimEnd()
            val titleStart = prefix + header.groups[3]!!.range.first
            lineMarks += EditorRange(prefix, titleStart)
            if (title.isNotEmpty()) {
                spans += EditorSpan(titleStart, titleStart + title.length, EditorStyle.CALLOUT_TITLE)
                inline(text, titleStart, titleStart + title.length, spans, elements)
            }
            val type = header.groupValues[1]
            return read(
                words = titleStart,
                callout =
                    EditorCallout(
                        kind = CalloutKind.parse(type),
                        title = title.ifEmpty { type.lowercase().replaceFirstChar { it.uppercase() } },
                        titled = title.isNotEmpty(),
                    ),
            )
        }

        FENCE_OPEN.find(body)?.let { open ->
            val fence = open.groupValues[1]
            // A backtick fence's language holds no backtick: that would be a code span.
            if (fence[0] == '~' || '`' !in open.groupValues[2]) {
                spans += EditorSpan(prefix, text.length, EditorStyle.CODE)
                return read(
                    kind = EditorLineKind.FENCE,
                    next = after.copy(block = EditorState.Block.FENCE, fence = fence, fenceQuoted = prefix > 0),
                )
            }
        }
        if (prefix == 0 && body.trim() == "$$") {
            return plain(text, rtl, after.copy(block = EditorState.Block.MATH))
        }
        if (prefix == 0 && body.trimStart().startsWith("%%") && body.indexOf("%%", body.indexOf("%%") + 2) < 0) {
            return plain(text, rtl, after.copy(block = EditorState.Block.COMMENT))
        }

        HEADING.find(body)?.let { heading ->
            val level = heading.groupValues[2].length
            val start = prefix + heading.value.length
            lineMarks += EditorRange(prefix + heading.groupValues[1].length, start)
            if (start < text.length) spans += EditorSpan(start, text.length, EditorStyle.HEADING)
            inline(text, start, text.length, spans, elements)
            return read(kind = EditorLineKind.HEADING, level = level)
        }
        if (RULE.matches(body)) {
            spans += EditorSpan(prefix, text.length, EditorStyle.RULE)
            return read()
        }
        var start = prefix
        LIST.find(body)?.let { item ->
            val marker = item.groups[2]!!.range
            spans += EditorSpan(prefix + marker.first, prefix + marker.last + 1, EditorStyle.LIST_MARKER)
            start = prefix + item.value.length
        }
        // The box is no text: `[*]` opens no italic.
        val box = TASK.find(body)
        if (box != null) start = prefix + box.value.length
        inline(text, start, text.length, spans, elements)
        return read(task = box?.let { task(text, prefix, it, spans) })
    }

    /** The task whose box [box] found in the line's text after its [prefix] of quote marks. */
    private fun task(
        text: String,
        prefix: Int,
        box: MatchResult,
        spans: List<EditorSpan>,
    ): EditorTask {
        val brackets = box.groups[2]!!.range
        val marker = box.groups[1]?.range
        val from = prefix + box.value.length
        // Inline code, math and a comment hold no chip.
        val plain =
            spans.filter {
                it.style == EditorStyle.CODE ||
                    it.style == EditorStyle.MATH ||
                    it.style == EditorStyle.COMMENT
            }
        val chips = chips(text, from).filter { chip -> plain.none { chip.start < it.end && chip.end > it.start } }
        val left =
            chips.map { EditorRange(it.start, it.end) } +
                REASON.findAll(text, from).map { EditorRange(it.range.first, it.range.last + 1) }
        return EditorTask(
            box = EditorRange(prefix + (marker ?: brackets).first, prefix + brackets.last + 1),
            status = box.groupValues[3].single(),
            chips = chips,
            text = between(text, from, left.sortedBy { it.start }),
        )
    }

    /** The ranges of [text] from [from] on that are in none of [left], each with the spaces at its ends taken off. */
    private fun between(
        text: String,
        from: Int,
        left: List<EditorRange>,
    ): List<EditorRange> {
        val out = ArrayList<EditorRange>()
        var at = from
        for (range in left + EditorRange(text.length, text.length)) {
            var start = at
            var end = minOf(range.start, text.length)
            while (start < end && text[start] == ' ') start++
            while (end > start && text[end - 1] == ' ') end--
            if (start < end) out += EditorRange(start, end)
            at = maxOf(at, range.end)
        }
        return out
    }

    /**
     * The date chips of a task's text from [from] on: an emoji, one space and
     * a real day, and a repeat's emoji with its rule.
     */
    private fun chips(
        text: String,
        from: Int,
    ): List<EditorChip> {
        var out: ArrayList<EditorChip>? = null
        var i = from
        while (i < text.length) {
            if (text[i] < FIRST_SYMBOL) {
                i++
                continue
            }
            val chip = dateChip(text, i) ?: repeatChip(text, i)
            if (chip == null) {
                i++
            } else {
                out = (out ?: ArrayList()).also { it += chip }
                i = chip.end
            }
        }
        return out ?: emptyList()
    }

    private fun dateChip(
        text: String,
        at: Int,
    ): EditorChip? {
        val (emoji, kind) = DATES.firstOrNull { text.startsWith(it.first, at) } ?: return null
        var day = at + emoji.length
        if (day < text.length && text[day] == VARIATION_SELECTOR) day++
        if (day >= text.length || text[day] != ' ') return null
        day++
        val end = day + DAY_LENGTH
        if (end > text.length || (end < text.length && text[end].isDigit())) return null
        if (!DAY.matches(text.subSequence(day, end))) return null
        // A day that is not real is no chip: the audit names it.
        val date = runCatching { LocalDate.parse(text.subSequence(day, end)) }.getOrNull() ?: return null
        return EditorChip(at, end, kind, date)
    }

    /** A repeat's rule ends before the next emoji of a task, before a block id, or at the line's end. */
    private fun repeatChip(
        text: String,
        at: Int,
    ): EditorChip? {
        if (!text.startsWith(REPEAT, at)) return null
        var end = BLOCK_ID.find(text, at)?.range?.first ?: text.length
        for (symbol in DATES.map { it.first } + PRIORITIES + REPEAT) {
            val next = text.indexOf(symbol, at + REPEAT.length)
            if (next in 0 until end) end = next
        }
        while (end > at && text[end - 1] == ' ') end--
        // The emoji alone has no rule.
        if (end <= at + REPEAT.length) return null
        return EditorChip(at, end, EditorChipKind.REPEAT)
    }

    /** Reads the inline marks of [text] from [from] until [to]. A parent's span is added before its children's. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun inline(
        text: String,
        from: Int,
        to: Int,
        spans: MutableList<EditorSpan>,
        elements: MutableList<EditorElement>,
    ) {
        var i = from
        while (i < to) {
            val c = text[i]
            val next = if (i + 1 < to) text[i + 1] else '\u0000'
            i =
                when {
                    c == '\\' -> i + 2
                    c == '`' -> codeSpan(text, i, to, spans, elements)
                    c == '$' -> math(text, i, to, spans)
                    c == '%' && next == '%' -> comment(text, i, to, spans)
                    c == '!' && next == '[' -> link(text, i, i + 1, to, spans, elements)
                    c == '[' -> link(text, i, i, to, spans, elements)
                    c == '<' -> autolink(text, i, to, spans)
                    c == 'h' && (i == from || !text[i - 1].isLetterOrDigit()) -> url(text, i, to, spans)
                    c == '#' && (i == from || text[i - 1].isWhitespace()) -> tag(text, i, to, spans)
                    c == '*' || c == '_' -> emphasis(text, i, to, spans, elements)
                    c == '~' && next == '~' -> pair(text, i, to, EditorStyle.STRIKE, spans, elements)
                    c == '=' && next == '=' -> pair(text, i, to, EditorStyle.HIGHLIGHT, spans, elements)
                    else -> i + 1
                }
        }
    }

    private fun codeSpan(
        text: String,
        at: Int,
        to: Int,
        spans: MutableList<EditorSpan>,
        elements: MutableList<EditorElement>,
    ): Int {
        val run = runLength(text, at, to)
        val close = closingBackticks(text, at + run, to, run)
        if (close < 0) return at + run
        spans += EditorSpan(at + run, close, EditorStyle.CODE)
        elements += EditorElement(at, close + run, listOf(EditorRange(at, at + run), EditorRange(close, close + run)))
        return close + run
    }

    /** Where a run of exactly [run] backticks starts after [from], or -1. */
    private fun closingBackticks(
        text: String,
        from: Int,
        to: Int,
        run: Int,
    ): Int {
        var j = from
        while (j < to) {
            if (text[j] == '`') {
                val length = runLength(text, j, to)
                if (length == run) return j
                j += length
            } else {
                j++
            }
        }
        return -1
    }

    private fun math(
        text: String,
        at: Int,
        to: Int,
        spans: MutableList<EditorSpan>,
    ): Int {
        val run = minOf(runLength(text, at, to), 2)
        val start = at + run
        if (start >= to || text[start].isWhitespace()) return start
        var j = start + 1
        while (j < to) {
            if (text[j] == '\\') {
                j += 2
                continue
            }
            if (text[j] == '$' && !text[j - 1].isWhitespace() && runLength(text, j, to) >= run) {
                // `$5 and $6` is money, not math.
                if (run == 1 && j + 1 < to && text[j + 1].isDigit()) return start
                spans += EditorSpan(at, j + run, EditorStyle.MATH)
                return j + run
            }
            j++
        }
        return start
    }

    private fun comment(
        text: String,
        at: Int,
        to: Int,
        spans: MutableList<EditorSpan>,
    ): Int {
        val close = text.indexOf("%%", at + 2)
        if (close < 0 || close + 2 > to) return at + 2
        spans += EditorSpan(at, close + 2, EditorStyle.COMMENT)
        return close + 2
    }

    /**
     * A link whose `[` is at [open]: `[[note|shown]]` or `[shown](address)`,
     * with a `!` before it at [at] when it is an embed or a picture.
     */
    private fun link(
        text: String,
        at: Int,
        open: Int,
        to: Int,
        spans: MutableList<EditorSpan>,
        elements: MutableList<EditorElement>,
    ): Int {
        if (open + 1 < to && text[open + 1] == '[') {
            val close = text.indexOf("]]", open + 2)
            if (close < 0 || close + 2 > to || close == open + 2) return open + 2
            // The bar of an alias; in a table it is written `\|`.
            val bar = text.indexOf('|', open + 2).takeIf { it in 0 until close }
            val shown = if (bar == null) open + 2 else bar + 1
            val marks = arrayListOf(EditorRange(at, shown), EditorRange(close, close + 2))
            if (shown >= close) return close + 2
            spans += EditorSpan(shown, close, EditorStyle.LINK)
            elements += EditorElement(at, close + 2, marks)
            return close + 2
        }
        // A footnote, a task's box: not a link.
        val label = closingBracket(text, open + 1, to)
        if (label < 0 || label + 1 >= to || text[label + 1] != '(' || label == open + 1) return open + 1
        val close = closingParen(text, label + 2, to)
        if (close < 0) return open + 1
        spans += EditorSpan(open + 1, label, EditorStyle.LINK)
        elements += EditorElement(at, close + 1, listOf(EditorRange(at, open + 1), EditorRange(label, close + 1)))
        inline(text, open + 1, label, spans, elements)
        return close + 1
    }

    private fun closingBracket(
        text: String,
        from: Int,
        to: Int,
    ): Int {
        var depth = 0
        var j = from
        while (j < to) {
            when (text[j]) {
                '\\' -> j++
                '[' -> depth++
                ']' -> if (depth == 0) return j else depth--
            }
            j++
        }
        return -1
    }

    private fun closingParen(
        text: String,
        from: Int,
        to: Int,
    ): Int {
        var depth = 0
        var j = from
        while (j < to) {
            when (text[j]) {
                '\\' -> j++
                '(' -> depth++
                ')' -> if (depth == 0) return j else depth--
            }
            j++
        }
        return -1
    }

    private fun autolink(
        text: String,
        at: Int,
        to: Int,
        spans: MutableList<EditorSpan>,
    ): Int {
        val close = text.indexOf('>', at + 1)
        if (close < 0 || close >= to) return at + 1
        val inner = text.substring(at + 1, close)
        if (inner.any { it.isWhitespace() } || ("://" !in inner && '@' !in inner)) return at + 1
        spans += EditorSpan(at + 1, close, EditorStyle.LINK)
        return close + 1
    }

    private fun url(
        text: String,
        at: Int,
        to: Int,
        spans: MutableList<EditorSpan>,
    ): Int {
        val match = URL.matchAt(text, at) ?: return at + 1
        val end = minOf(match.range.last + 1, to)
        spans += EditorSpan(at, end, EditorStyle.LINK)
        return end
    }

    private fun tag(
        text: String,
        at: Int,
        to: Int,
        spans: MutableList<EditorSpan>,
    ): Int {
        val match = TAG.matchAt(text, at) ?: return at + 1
        val end = minOf(match.range.last + 1, to)
        // `#12` is a number, as it is to the reader.
        if (text.substring(at + 1, end).all { it.isDigit() }) return end
        spans += EditorSpan(at, end, EditorStyle.TAG)
        return end
    }

    /** `*` and `_`: two for bold, one for italic, three for both. */
    private fun emphasis(
        text: String,
        at: Int,
        to: Int,
        spans: MutableList<EditorSpan>,
        elements: MutableList<EditorElement>,
    ): Int {
        val mark = text[at]
        val run = runLength(text, at, to)
        val opens =
            at + run < to &&
                !text[at + run].isWhitespace() &&
                (mark == '*' || at == 0 || !text[at - 1].isLetterOrDigit())
        if (!opens) return at + run
        for (size in if (run >= 2) intArrayOf(2, 1) else intArrayOf(1)) {
            val close = closer(text, at + size, to, mark, size)
            if (close < 0) continue
            spans += EditorSpan(at + size, close, if (size == 2) EditorStyle.STRONG else EditorStyle.EMPHASIS)
            val marks = listOf(EditorRange(at, at + size), EditorRange(close, close + size))
            elements += EditorElement(at, close + size, marks)
            inline(text, at + size, close, spans, elements)
            return close + size
        }
        return at + run
    }

    /** `~~struck~~` and `==marked==`, with a highlight's colour emoji as part of its opening mark. */
    private fun pair(
        text: String,
        at: Int,
        to: Int,
        style: EditorStyle,
        spans: MutableList<EditorSpan>,
        elements: MutableList<EditorElement>,
    ): Int {
        val mark = text[at]
        if (at + 2 >= to || text[at + 2].isWhitespace()) return at + 2
        val close = closer(text, at + 2, to, mark, 2)
        if (close < 0) return at + 2
        var start = at + 2
        var color: HighlightColor? = null
        if (style == EditorStyle.HIGHLIGHT) {
            color = HighlightColor.entries.firstOrNull { text.startsWith(it.emoji, start) }
            if (color != null) {
                var content = start + color.emoji.length
                if (content < close && text[content] == VARIATION_SELECTOR) content++
                while (content < close && text[content] == ' ') content++
                // A highlight that holds only the emoji is a plain one, as the reader has it.
                if (content < close) start = content else color = null
            }
        }
        spans += EditorSpan(start, close, style, color)
        elements += EditorElement(at, close + 2, listOf(EditorRange(at, start), EditorRange(close, close + 2)))
        inline(text, start, close, spans, elements)
        return close + 2
    }

    /**
     * Where the [size] marks that close an element start, looking from [from]:
     * after a character that is not a space, and not inside a code span.
     */
    private fun closer(
        text: String,
        from: Int,
        to: Int,
        mark: Char,
        size: Int,
    ): Int {
        var j = from
        while (j < to) {
            val c = text[j]
            when {
                c == '\\' -> j += 2
                c == '`' -> {
                    val run = runLength(text, j, to)
                    val close = closingBackticks(text, j + run, to, run)
                    j = if (close < 0) j + run else close + run
                }
                c == mark -> {
                    val run = runLength(text, j, to)
                    val at = closerIn(run, size, mark)
                    val wordAfter = mark == '_' && j + run < to && text[j + run].isLetterOrDigit()
                    if (at >= 0 && j > from && !text[j - 1].isWhitespace() && !wordAfter) return j + at
                    j += run
                }
                else -> j++
            }
        }
        return -1
    }

    /** Where in a run of [run] marks the closing [size] start, or -1 when the run cannot close them. */
    private fun closerIn(
        run: Int,
        size: Int,
        mark: Char,
    ): Int =
        when {
            mark != '*' && mark != '_' -> if (run >= size) 0 else -1
            // The inner marks of `***both***` close first.
            size == 2 -> if (run >= 2) run - 2 else -1
            run == 1 -> 0
            run == 2 -> -1
            else -> run - 1
        }

    private fun runLength(
        text: String,
        at: Int,
        to: Int,
    ): Int {
        var j = at
        while (j < to && text[j] == text[at]) j++
        return j - at
    }
}
