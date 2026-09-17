package me.parham1995.notes.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.PostProcessor

/**
 * Rewrites `> [!type] Title` blockquotes into [CalloutNode]s.
 *
 * An Obsidian callout *is* a blockquote -- there is no separate block syntax --
 * so this runs after parsing rather than as a block parser. Doing it any other
 * way means reimplementing blockquote handling, including the lazy
 * continuation rules and the fenced code blocks that appear inside callouts.
 *
 * The header runs to the first line break, which is what makes a malformed
 * callout (body glued to the header with no `>` separator) degrade the way
 * Obsidian degrades it rather than crashing: the first line becomes the title
 * and the rest stays as body.
 */
class CalloutPostProcessor : PostProcessor {
    override fun process(node: Node): Node {
        node.accept(Visitor())
        return node
    }

    private class Visitor : AbstractVisitor() {
        override fun visit(blockQuote: BlockQuote) {
            // Depth first, so a nested callout is rewritten before its parent.
            visitChildren(blockQuote)
            rewrite(blockQuote)
        }
    }

    companion object {
        private val HEADER = Regex("""^\[!([A-Za-z_-]+)\]([+-]?)[ \t]*(.*)$""")

        private fun rewrite(quote: BlockQuote) {
            val paragraph = quote.firstChild as? Paragraph ?: return
            val firstText = paragraph.firstChild as? Text ?: return
            val match = HEADER.find(firstText.literal) ?: return

            val kind = CalloutKind.parse(match.groupValues[1])
            val collapsed = match.groupValues[2] == "-"

            // Everything after the marker, up to the first line break, is the
            // title; the remainder of the paragraph stays as body.
            firstText.literal = match.groupValues[3]

            val titleParts = mutableListOf<Node>()
            var cursor: Node? = paragraph.firstChild
            while (cursor != null && cursor !is SoftLineBreak) {
                val next = cursor.next
                titleParts += cursor
                cursor = next
            }
            // Drop the break itself so the body does not start with one.
            cursor?.unlink()

            val titleText =
                buildString {
                    titleParts.forEach { part -> appendPlainText(part, this) }
                }.trim()
            titleParts.forEach { it.unlink() }

            if (paragraph.firstChild == null) paragraph.unlink()

            val callout = CalloutNode(kind, titleText, collapsed)
            var child: Node? = quote.firstChild
            while (child != null) {
                val next = child.next
                callout.appendChild(child)
                child = next
            }
            quote.insertAfter(callout)
            quote.unlink()
        }

        private fun appendPlainText(
            node: Node,
            out: StringBuilder,
        ) {
            if (node is Text) out.append(node.literal)
            var child = node.firstChild
            while (child != null) {
                appendPlainText(child, out)
                child = child.next
            }
        }
    }
}
