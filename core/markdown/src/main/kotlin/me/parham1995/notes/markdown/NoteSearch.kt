package me.parham1995.notes.markdown

/** One place a search term appears, and the block it is in. */
data class NoteMatch(
    val blockIndex: Int,
    /** A short run of the block's text around the hit, for the results list. */
    val preview: String,
    /** Where the term starts inside [preview], so it can be marked. */
    val previewStart: Int,
    val previewEnd: Int,
)

/**
 * Finding a term inside one note.
 *
 * Search already answers "which note", which is a different question from
 * "where in this one". 129 of this vault's notes run past 20KB and the longest
 * is 89KB -- long enough that knowing the word is in there is not the same as
 * being able to get to it.
 *
 * Matching is on the block's plain text, not the markdown, so a search for a
 * phrase finds it whether or not someone bolded a word in the middle of it.
 */
object NoteSearch {
    private const val CONTEXT = 40

    fun find(
        blocks: List<MdBlock>,
        query: String,
    ): List<NoteMatch> {
        val matches = mutableListOf<NoteMatch>()
        blocks.forEachIndexed { index, block ->
            val text = block.searchableText()
            for (hit in ranges(text, query)) {
                val start = (hit.first - CONTEXT).coerceAtLeast(0)
                val end = (hit.last + 1 + CONTEXT).coerceAtMost(text.length)
                matches +=
                    NoteMatch(
                        blockIndex = index,
                        preview = text.substring(start, end),
                        previewStart = hit.first - start,
                        previewEnd = hit.last + 1 - start,
                    )
            }
        }
        return matches
    }

    /**
     * Where [query] appears in [text], in [text]'s own offsets.
     *
     * The same matching [find] counts with, so the reader can mark on the
     * page exactly the hits the find bar numbers -- a word typed on a Persian
     * keyboard is marked where the note wrote it with Arabic letters.
     */
    fun ranges(
        text: String,
        query: String,
    ): List<IntRange> {
        // Folded the way search across the vault folds it, so a word typed on
        // a Persian keyboard is found where the note wrote it with Arabic
        // letters, or with a zero-width joiner the query left out. The match
        // is made on the folded text and carried back, so the preview and its
        // highlight are in the note's own letters.
        val term = SearchText.normalize(query.trim())
        if (term.isEmpty() || text.isEmpty()) return emptyList()
        val folded = SearchText.mapped(text)
        val hits = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val found = folded.text.indexOf(term, from, ignoreCase = true)
            if (found < 0) break
            val at = folded.originOf(found)
            val until = folded.originOf(found + term.length)
            if (until > at) hits += at until until
            from = found + term.length
        }
        return hits
    }

    /**
     * The text a reader can see in this block.
     *
     * Code is included -- a third of this vault's lines are inside fences, and
     * looking for a flag or a hostname is exactly when this is wanted. Front
     * matter is not: it is metadata the reader never shows.
     */
    private fun MdBlock.searchableText(): String =
        when (this) {
            is MdBlock.Paragraph -> inlines.plainText()
            is MdBlock.Heading -> inlines.plainText()
            is MdBlock.CodeBlock -> code
            is MdBlock.MathBlock -> latex
            is MdBlock.Mermaid -> code
            is MdBlock.Quote -> children.joinToString(" ") { it.searchableText() }
            is MdBlock.Callout ->
                (title.plainText() + " " + children.joinToString(" ") { it.searchableText() }).trim()
            is MdBlock.ListBlock ->
                items.joinToString(" ") { item ->
                    item.blocks.joinToString(" ") { it.searchableText() }
                }
            is MdBlock.Table ->
                (header + rows.flatten()).joinToString(" ") { it.plainText() }
            is MdBlock.Attachment -> label
            is MdBlock.NoteEmbed -> label
            is MdBlock.Image -> alt.orEmpty()
            is MdBlock.Footnotes ->
                entries.joinToString(
                    " ",
                ) { entry -> entry.blocks.joinToString(" ") { it.searchableText() } }
            else -> ""
        }

    private fun List<MdInline>.plainText(): String =
        joinToString("") { node ->
            when (node) {
                is MdInline.Text -> node.text
                is MdInline.Code -> node.code
                is MdInline.WikiLink -> node.alias ?: node.target
                is MdInline.Link -> node.children.plainText()
                is MdInline.Emphasis -> node.children.plainText()
                is MdInline.Strong -> node.children.plainText()
                is MdInline.Highlight -> node.children.plainText()
                is MdInline.Strikethrough -> node.children.plainText()
                is MdInline.Tag -> "#" + node.name
                else -> ""
            }
        }
}
