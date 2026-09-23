package me.parham1995.notes.markdown

import org.commonmark.node.Document
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.PostProcessor

/**
 * Removes Obsidian's `%%comments%%`, inline and block alike.
 *
 * A comment is for the author and never for the reader, and it can run across
 * paragraphs, lists and headings -- a block comment is `%%` on a line, then
 * anything at all, then `%%`. So this works on the finished tree in document
 * order rather than on one paragraph at a time: every node between an opening
 * and a closing marker is taken out, whatever block it is in, and a block left
 * with nothing in it goes too.
 *
 * Working on the tree rather than the source is what keeps the markers out of
 * code: a code span or a fence is a node with a literal, never [Text], so the
 * `%%` that starts a mermaid comment is never seen. It also leaves the source
 * untouched, so the line a task was written on is still the line it reports.
 *
 * An unmatched marker is text. Obsidian hides everything after one, which is
 * right for a comment someone forgot to close and wrong for "50%%" in prose --
 * and losing the rest of a note to a typo is the worse of the two mistakes.
 */
class CommentPostProcessor : PostProcessor {
    override fun process(node: Node): Node {
        val total = countMarkers(node)
        // An odd count leaves the last one unmatched, and so ordinary text.
        val usable = total - total % 2
        if (usable == 0) return node
        Remover(usable).walk(node)
        return node
    }

    private fun countMarkers(node: Node): Int {
        var count = 0
        if (node is Text) count += markersIn(node.literal).size
        var child = node.firstChild
        while (child != null) {
            count += countMarkers(child)
            child = child.next
        }
        return count
    }

    private class Remover(
        private val usable: Int,
    ) {
        private var seen = 0
        private var inComment = false

        fun walk(node: Node) {
            var child = node.firstChild
            while (child != null) {
                val next = child.next
                visit(child)
                child = next
            }
        }

        private fun visit(node: Node) {
            when {
                node is Text -> text(node)
                node.firstChild == null -> if (inComment) remove(node)
                else -> {
                    walk(node)
                    if (node.isEmptied()) remove(node)
                }
            }
        }

        private fun text(node: Text) {
            val literal = node.literal
            val markers = markersIn(literal)
            if (markers.isEmpty() || seen >= usable) {
                if (inComment) remove(node)
                return
            }
            val kept = StringBuilder()
            var cursor = 0
            for (at in markers) {
                if (seen >= usable) break
                if (!inComment) kept.append(literal, cursor, at)
                inComment = !inComment
                seen++
                cursor = at + MARKER.length
            }
            if (!inComment) kept.append(literal, cursor, literal.length)
            if (kept.isEmpty()) remove(node) else node.literal = kept.toString()
        }

        /**
         * Unlinks [node], and any block that was holding nothing else.
         *
         * Only ancestors of something removed are ever considered, so a list
         * item that was empty in the file stays exactly as it was written.
         */
        private fun remove(node: Node) {
            var current: Node? = node
            while (current != null && current !is Document) {
                val parent = current.parent
                current.unlink()
                current = parent?.takeIf { it !is Document && it.isEmptied() }
            }
        }

        private fun Node.isEmptied(): Boolean {
            if (firstChild == null) return true
            if (this !is Paragraph) return false
            // A paragraph that was a comment and a line break is not one.
            var child = firstChild
            while (child != null) {
                val blank =
                    child is SoftLineBreak ||
                        child is HardLineBreak ||
                        (child is Text && child.literal.isBlank())
                if (!blank) return false
                child = child.next
            }
            return true
        }
    }

    private companion object {
        const val MARKER = "%%"

        fun markersIn(literal: String): List<Int> {
            val found = mutableListOf<Int>()
            var at = literal.indexOf(MARKER)
            while (at >= 0) {
                found += at
                at = literal.indexOf(MARKER, at + MARKER.length)
            }
            return found
        }
    }
}
