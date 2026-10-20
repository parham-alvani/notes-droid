package me.parham1995.notes.markdown

import kotlin.random.Random

/**
 * One `[!quote]` callout, as the quote widget shows it.
 *
 * [lines] are the callout's paragraphs as plain text. [source] is the last
 * paragraph when it reads as an attribution -- a dash first, `— James Clear`
 * -- and is drawn smaller under the words rather than as one of them.
 */
data class Quote(
    val lines: List<String>,
    val source: String? = null,
)

/**
 * The quotes in a note, and which of them is today's.
 *
 * A quote is a `[!quote]` callout, so a note of them reads in Obsidian the way
 * it reads here and needs no format of its own. Everything else in the note --
 * an introduction, headings, a remark between two quotes -- is left out.
 */
object Quotes {
    /** Every `[!quote]` callout in [markdown], in the order the note has them. */
    fun parse(markdown: String): List<Quote> {
        val found = ArrayList<Quote>()
        collect(MarkdownParser.parseNote(markdown).blocks, found)
        return found
    }

    /**
     * The quote for [epochDay], with [skip] more taps of "next" on top.
     *
     * The same all day, so a redraw does not change it under the reader, and
     * every quote comes round once before any comes round twice. The order
     * within each round is shuffled, seeded by the round, so the note's order
     * is not the order of the days.
     */
    fun forDay(
        quotes: List<Quote>,
        epochDay: Long,
        skip: Int = 0,
    ): Quote? {
        if (quotes.isEmpty()) return null
        val size = quotes.size.toLong()
        val day = epochDay + skip
        val round = Math.floorDiv(day, size)
        val position = Math.floorMod(day, size).toInt()
        val order = quotes.indices.shuffled(Random(round))
        return quotes[order[position]]
    }

    private fun collect(
        blocks: List<MdBlock>,
        into: MutableList<Quote>,
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Callout ->
                    if (block.kind == CalloutKind.QUOTE) {
                        quoteOf(block)?.let(into::add)
                    } else {
                        collect(block.children, into)
                    }
                is MdBlock.Quote -> collect(block.children, into)
                is MdBlock.ListBlock -> block.items.forEach { collect(it.blocks, into) }
                else -> Unit
            }
        }
    }

    private fun quoteOf(callout: MdBlock.Callout): Quote? {
        val paragraphs =
            callout.children
                .filterIsInstance<MdBlock.Paragraph>()
                .map { plainText(it.inlines).trim() }
                .filter { it.isNotEmpty() }
        // A one-line callout written in the title slot is still a quote.
        val words = paragraphs.ifEmpty { listOfNotNull(plainText(callout.title).trim().ifEmpty { null }) }
        if (words.isEmpty()) return null
        val last = words.last()
        val dash = SOURCE_DASHES.firstOrNull { last.startsWith(it) }
        if (dash == null || words.size == 1) return Quote(words)
        return Quote(words.dropLast(1), last.removePrefix(dash).trim().ifEmpty { null })
    }

    /** What an attribution starts with: an em dash, an en dash, or two hyphens typed for one. */
    private val SOURCE_DASHES = listOf("—", "–", "--", "―")
}
