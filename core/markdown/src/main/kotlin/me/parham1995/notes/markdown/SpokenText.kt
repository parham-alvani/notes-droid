package me.parham1995.notes.markdown

/**
 * A note as it would be read out: its words, in order, one utterance per
 * block, and nothing a voice cannot say.
 *
 * Code, diagrams, formulas, pictures and front matter are skipped rather than
 * spelled out -- a voice reading `kubectl get pods -n kube-system` character
 * by character is not reading the note. Each utterance says whether it is
 * Persian, because the two languages need different voices and a note in this
 * vault is often both, a paragraph at a time.
 */
object SpokenText {
    data class Utterance(
        val text: String,
        val persian: Boolean,
    )

    fun of(blocks: List<MdBlock>): List<Utterance> =
        buildList<String> { blocks.forEach { spoken(it) } }
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { text -> text.any { it.isLetterOrDigit() } }
            .flatMap { split(it) }
            .map { Utterance(it, isPersian(it)) }

    private fun MutableList<String>.spoken(block: MdBlock) {
        when (block) {
            is MdBlock.Heading -> add(plainText(block.inlines))
            is MdBlock.Paragraph -> add(plainText(block.inlines))
            is MdBlock.Quote -> block.children.forEach { spoken(it) }
            is MdBlock.Callout -> {
                add(plainText(block.title))
                block.children.forEach { spoken(it) }
            }
            is MdBlock.ListBlock -> block.items.forEach { item -> item.blocks.forEach { spoken(it) } }
            // A row at a time, its cells as a list: "Rent, 1450, monthly".
            is MdBlock.Table ->
                (listOf(block.header) + block.rows).forEach { row ->
                    add(row.joinToString(", ") { plainText(it).trim() })
                }
            else -> Unit
        }
    }

    /**
     * Whether a run of text is mostly written in Arabic script, which in this
     * vault means Persian. Mostly, not at all: an English sentence naming one
     * Persian word is still read by the English voice.
     */
    fun isPersian(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        return letters.count { it in '؀'..'ۿ' || it in 'ﭐ'..'﷿' } * 2 > letters.length
    }

    /**
     * Engines refuse an utterance over a few thousand characters, and a long
     * paragraph would otherwise be skipped whole. Cut at sentence ends where
     * there are any.
     */
    private fun split(text: String): List<String> {
        if (text.length <= MAX_CHARS) return listOf(text)
        val pieces = mutableListOf<String>()
        var rest = text
        while (rest.length > MAX_CHARS) {
            val window = rest.substring(0, MAX_CHARS)
            val cut =
                SENTENCE_END
                    .findAll(window)
                    .lastOrNull()
                    ?.range
                    ?.last
                    ?.plus(1)
                    ?: window.lastIndexOf(' ').takeIf { it > 0 }
                    ?: MAX_CHARS
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) pieces += rest.trim()
        return pieces
    }

    private val WHITESPACE = Regex("\\s+")
    private val SENTENCE_END = Regex("[.!?؟۔]\\s")
    private const val MAX_CHARS = 3000
}
