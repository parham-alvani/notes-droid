package me.parham1995.notes.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor

/**
 * Lifts Obsidian's inline `#tags` out of the text.
 *
 * A post-processor over [Text] nodes for the same reason as the wikilinks: a
 * `#word` inside a code span or a fence is never a [Text], so it is never a
 * tag, and a heading's own `#` markers are gone before the text is built.
 * Registered after [WikiLinkExtension], so the `#` in `[[Note#Heading]]` has
 * already become part of a link by the time this looks.
 *
 * Text inside a link is skipped: `[#1](url)` is a label, and an autolinked
 * `https://example.com/#top` is an address whose fragment only looks like one.
 */
class TagExtension private constructor() : Parser.ParserExtension {
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.postProcessor(Processor())
    }

    private class Processor : PostProcessor {
        override fun process(node: Node): Node {
            val found = mutableListOf<Text>()
            node.accept(
                object : AbstractVisitor() {
                    override fun visit(text: Text) {
                        if (text.literal?.contains('#') == true && !insideLink(text)) found += text
                    }
                },
            )
            // Split after the walk: rewriting the tree while visiting it skips
            // the nodes inserted behind the cursor.
            found.forEach { split(it) }
            return node
        }

        private fun insideLink(node: Node): Boolean {
            var parent = node.parent
            while (parent != null) {
                if (parent is Link || parent is Image) return true
                parent = parent.parent
            }
            return false
        }
    }

    companion object {
        fun create(): TagExtension = TagExtension()

        internal fun split(text: Text) {
            val literal = text.literal ?: return
            // At the start of a text node the `#` is only preceded by nothing
            // when the node starts a line. After `**bold**` it is glued to the
            // word before it, which Obsidian does not read as a tag either.
            val atLineStart = text.previous.let { it == null || it is SoftLineBreak || it is HardLineBreak }
            val matches =
                Tags.INLINE
                    .findAll(literal)
                    .filter { it.range.first > 0 || atLineStart }
                    .mapNotNull { match -> Tags.clean(match.groupValues[1])?.let { match to it } }
                    .toList()
            if (matches.isEmpty()) return

            var cursor = 0
            var anchor: Node = text
            val pending = mutableListOf<Node>()
            for ((match, name) in matches) {
                val before = literal.substring(cursor, match.range.first)
                if (before.isNotEmpty()) pending += Text(before)
                pending += TagNode(name)
                // A trailing slash is not part of the tag, so it is given back
                // to the text rather than dropped.
                cursor = match.range.first + 1 + name.length
            }
            val after = literal.substring(cursor)
            if (after.isNotEmpty()) pending += Text(after)

            pending.forEach { node ->
                anchor.insertAfter(node)
                anchor = node
            }
            text.unlink()
        }
    }
}

/** What Obsidian accepts as a tag, wherever it is written. */
object Tags {
    /**
     * `#` at the start or after whitespace, then letters, digits, `_`, `-` and
     * `/`. Anything else -- a full stop, a comma, a bracket -- ends the tag,
     * which is how `#idea.` at the end of a sentence works.
     */
    internal val INLINE = Regex("""(?<!\S)#([\p{L}\p{M}\p{N}_/\-]+)""")

    private val ALLOWED = Regex("""[\p{L}\p{M}\p{N}_/\-]+""")

    /**
     * The tag [raw] names, without its `#` and trailing slashes, or null when
     * it is not one.
     *
     * Obsidian's rule that a tag is not a number is what keeps `#1` in "issue
     * #1" and `#2024` out of the tag list; a tag has to have something in it
     * that is not a digit. A slash does not count, so `#2024/01` is a date.
     */
    fun clean(raw: String): String? {
        val name = raw.trim().removePrefix("#").trimEnd('/')
        if (name.isEmpty() || name.startsWith('/') || name.contains("//")) return null
        if (!ALLOWED.matches(name)) return null
        if (name.none { !it.isDigit() && it != '/' }) return null
        return name
    }

    /**
     * Tags named in front matter: a list, or one string split on commas and
     * spaces the way Obsidian reads `tags: a, b` and `tags: a b`. A leading
     * `#` is allowed and dropped.
     */
    fun fromFrontMatter(properties: List<FrontMatterProperty>): List<String> =
        properties
            .filter { it.key.lowercase() in KEYS }
            .flatMap { property -> property.values.flatMap { it.split(SEPARATORS) } }
            .mapNotNull { clean(it) }

    /** Case-insensitively distinct, keeping the first spelling met. */
    fun distinct(tags: List<String>): List<String> = tags.distinctBy { Slugs.fold(it) }

    /** Every tag [tag] sits under, itself included: `a/b/c` is in `a` and `a/b`. */
    fun ancestry(tag: String): List<String> {
        val parts = tag.split('/')
        return parts.indices.map { parts.subList(0, it + 1).joinToString("/") }
    }

    private val KEYS = setOf("tags", "tag")
    private val SEPARATORS = Regex("""[,\s]+""")
}
