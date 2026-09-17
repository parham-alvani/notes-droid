package me.parham1995.notes.markdown

import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

/**
 * Obsidian's `==highlight==`. Structurally identical to strikethrough, so this
 * is the same shape as commonmark's own GFM extension.
 */
class HighlightExtension private constructor() : Parser.ParserExtension {
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.customDelimiterProcessor(Processor())
    }

    private class Processor : DelimiterProcessor {
        override fun getOpeningCharacter(): Char = '='

        override fun getClosingCharacter(): Char = '='

        // A single `=` is ordinary text -- it appears in prose, in key=value
        // pairs and in comparisons constantly.
        override fun getMinLength(): Int = 2

        override fun process(
            openingRun: DelimiterRun,
            closingRun: DelimiterRun,
        ): Int {
            if (openingRun.length() < MIN_RUN || closingRun.length() < MIN_RUN) return 0

            val opener: Text = openingRun.opener
            val closer: Text = closingRun.closer
            val highlight = HighlightNode()

            var node: Node? = opener.next
            while (node != null && node !== closer) {
                val next = node.next
                highlight.appendChild(node)
                node = next
            }
            opener.insertAfter(highlight)
            return MIN_RUN
        }

        private companion object {
            const val MIN_RUN = 2
        }
    }

    companion object {
        fun create(): HighlightExtension = HighlightExtension()
    }
}
