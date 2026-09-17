package me.parham1995.notes.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor

/**
 * Parses `[[wikilinks]]` and `![[embeds]]` out of the text.
 *
 * This runs as a post-processor rather than a custom inline parser, and that is
 * deliberate: `[` is already a special character in CommonMark's own inline
 * parser, so a custom parser registered against it is never consulted -- the
 * link machinery gets there first. (`$` is not special, which is why maths can
 * hook the character directly.)
 *
 * Working on the finished tree also gets the awkward cases right for free.
 * Only real text becomes a [Text] node, so a `[[wikilink]]` written inside a
 * code span or a fenced block -- of which a documentation-heavy vault has
 * plenty -- is never touched.
 */
class WikiLinkExtension private constructor() : Parser.ParserExtension {
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.postProcessor(Processor())
    }

    private class Processor : PostProcessor {
        override fun process(node: Node): Node {
            node.accept(Visitor())
            return node
        }
    }

    private class Visitor : AbstractVisitor() {
        override fun visit(text: Text) {
            split(text)
        }
    }

    companion object {
        fun create(): WikiLinkExtension = WikiLinkExtension()

        /** Targets never contain a bracket, so the inner class stays simple. */
        private val PATTERN = Regex("""(!?)\[\[([^\[\]]{1,512})]]""")

        internal fun split(text: Text) {
            val literal = text.literal ?: return
            if (!literal.contains("[[")) return

            val matches = PATTERN.findAll(literal).toList()
            if (matches.isEmpty()) return

            var cursor = 0
            var anchor: Node = text
            val pending = mutableListOf<Node>()

            for (match in matches) {
                val node = parseTarget(match.groupValues[2], embed = match.groupValues[1] == "!")
                if (node == null) continue

                val before = literal.substring(cursor, match.range.first)
                if (before.isNotEmpty()) pending += Text(before)
                pending += node
                cursor = match.range.last + 1
            }
            if (pending.isEmpty()) return

            val after = literal.substring(cursor)
            if (after.isNotEmpty()) pending += Text(after)

            pending.forEach { node ->
                anchor.insertAfter(node)
                anchor = node
            }
            text.unlink()
        }

        /**
         * A `\\|` in a table cell escapes the *table* delimiter, not the link.
         * CommonMark has already resolved that escape by the time the text
         * reaches here, so the first pipe is always the alias separator.
         */
        internal fun parseTarget(
            raw: String,
            embed: Boolean,
        ): WikiLinkNode? {
            val pipe = raw.indexOf('|')
            val targetPart = if (pipe >= 0) raw.substring(0, pipe) else raw
            val alias = if (pipe >= 0) raw.substring(pipe + 1).trim() else null

            val hash = targetPart.indexOf('#')
            // A handful of links carry a trailing slash; it is never part of
            // the name.
            val target = (if (hash >= 0) targetPart.substring(0, hash) else targetPart).trim().trimEnd('/')
            val heading = if (hash >= 0) targetPart.substring(hash + 1).trim() else null

            // `[[]]` is not a link. `[[#Heading]]` is -- an in-page jump.
            if (target.isEmpty() && heading.isNullOrEmpty()) return null

            return WikiLinkNode(
                target = target,
                heading = heading?.takeIf { it.isNotEmpty() },
                alias = alias?.takeIf { it.isNotEmpty() },
                embed = embed,
            )
        }
    }
}
