package me.parham1995.notes.markdown

import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline

/**
 * LaTeX, both `$$display$$` and `$inline$`.
 *
 * Inline maths outnumbers display maths roughly six to one in a real vault, so
 * it is not an afterthought. Running as an inline parser rather than a raw scan
 * matters: the parser only sees text, so `$HOME` and `$PATH` inside fenced code
 * and code spans never reach it. Scanning the source directly would match
 * hundreds of shell variables.
 */
class MathExtension private constructor() : Parser.ParserExtension {
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.customInlineContentParserFactory(Factory())
    }

    private class Factory : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf('$')

        override fun create(): InlineContentParser = MathInlineParser()
    }

    companion object {
        fun create(): MathExtension = MathExtension()
    }
}

internal class MathInlineParser : InlineContentParser {
    override fun tryParse(state: InlineParserState): ParsedInline? {
        val scanner = state.scanner()
        val start = scanner.position()

        if (!scanner.next('$')) {
            scanner.setPosition(start)
            return ParsedInline.none()
        }
        val display = scanner.next('$')

        val contentStart = scanner.position()
        var scanned = 0
        val limit = if (display) MAX_DISPLAY else MAX_INLINE
        while (scanner.hasNext() && scanned < limit) {
            if (scanner.peek() == '$') {
                val beforeClose = scanner.position()
                scanner.next()
                val closed = if (display) scanner.next('$') else true
                if (closed) {
                    val latex = scanner.getSource(contentStart, beforeClose).content.trim()
                    if (latex.isEmpty()) break
                    val node = if (display) DisplayMathNode(latex) else InlineMathNode(latex)
                    return ParsedInline.of(node, scanner.position())
                }
            } else {
                scanner.next()
            }
            scanned++
        }

        scanner.setPosition(start)
        return ParsedInline.none()
    }

    private companion object {
        /** A lone `$` in prose is a currency sign, not an unterminated formula. */
        const val MAX_INLINE = 256

        /** Display blocks wrap several lines of LaTeX. */
        const val MAX_DISPLAY = 4096
    }
}
