package me.parham1995.notes.markdown

import org.commonmark.ext.front.matter.YamlFrontMatterBlock
import org.commonmark.ext.front.matter.YamlFrontMatterNode
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

/**
 * Turns the parsed AST into the flat [MdBlock] list the UI renders.
 *
 * Flat is the point. A note becomes a `LazyColumn` over these blocks with one
 * `AnnotatedString` each, so only what is on screen is ever composed -- a
 * single string for a 139KB note would stall the frame that built it.
 */
class BlockFlattener(
    private val imageResolver: (String) -> String = { it },
) {
    private var nextId = 0
    private val headings = mutableListOf<ParsedHeading>()
    private val links = mutableListOf<ParsedLink>()
    private val plain = StringBuilder()
    private var hasMermaid = false
    private var hasMath = false
    private val frontMatter = linkedMapOf<String, String>()

    fun flatten(document: Node): ParsedNote {
        val blocks = mutableListOf<MdBlock>()
        var child = document.firstChild
        while (child != null) {
            blocks += blocksFor(child)
            child = child.next
        }

        val plainText = plain.toString().trim()
        return ParsedNote(
            blocks = blocks,
            title =
                headings.firstOrNull { it.level == 1 }?.text
                    ?: headings.firstOrNull()?.text.orEmpty(),
            headings = headings.toList(),
            links = links.toList(),
            plainText = plainText,
            frontMatter = frontMatter.toMap(),
            isRtl = TextDirection.containsRtl(plainText),
            hasMermaid = hasMermaid,
            hasMath = hasMath,
        )
    }

    private fun id() = nextId++

    private fun blocksFor(node: Node): List<MdBlock> =
        when (node) {
            is Heading -> listOf(heading(node))
            is Paragraph -> paragraph(node)
            is FencedCodeBlock -> listOf(fence(node))
            is CalloutNode -> listOf(callout(node))
            is BlockQuote -> listOf(quote(node))
            is BulletList -> listOf(list(node, ordered = false, start = 1))
            is OrderedList -> listOf(list(node, ordered = true, start = node.markerStartNumber ?: 1))
            is TableBlock -> listOf(table(node))
            is ThematicBreak -> listOf(MdBlock.ThematicBreak(id()))
            is YamlFrontMatterBlock -> listOf(frontMatter(node))
            is HtmlBlock -> htmlBlock(node)
            else -> emptyList()
        }

    private fun heading(node: Heading): MdBlock.Heading {
        val inlines = inlines(node)
        val text = plainTextOf(node)
        plain.append(text).append('\n')
        val block =
            MdBlock.Heading(
                id = id(),
                level = node.level,
                inlines = inlines,
                text = text,
                direction = TextDirection.of(text),
            )
        headings += ParsedHeading(node.level, text, Slugs.heading(text), nextId - 1)
        return block
    }

    /**
     * A paragraph holding nothing but a display formula or a single embed is
     * promoted to a block of its own, so it can be centred, zoomed and cached
     * rather than squeezed into a line of text.
     */
    private fun paragraph(node: Paragraph): List<MdBlock> {
        val children = node.children()
        val meaningful = children.filterNot { it is SoftLineBreak }

        (meaningful.singleOrNull() as? DisplayMathNode)?.let {
            hasMath = true
            return listOf(MdBlock.MathBlock(id(), it.latex))
        }
        (meaningful.singleOrNull() as? WikiLinkNode)?.takeIf { it.embed }?.let { return listOf(embed(it)) }
        (meaningful.singleOrNull() as? Image)?.let { image ->
            val alt = plainTextOf(image).takeIf { it.isNotBlank() }
            links += ParsedLink(LinkKind.IMAGE, image.destination, alt)
            return listOf(MdBlock.Image(id(), imageResolver(image.destination), alt))
        }

        val text = plainTextOf(node)
        plain.append(text).append('\n')
        return listOf(MdBlock.Paragraph(id(), inlines(node), TextDirection.of(text)))
    }

    private fun embed(node: WikiLinkNode): MdBlock {
        links += ParsedLink(LinkKind.WIKI_EMBED, node.target, node.alias)
        val extension = node.target.substringAfterLast('.', "").lowercase()
        return if (extension in RENDERABLE_IMAGES) {
            MdBlock.Image(id(), imageResolver(node.target), node.alias)
        } else {
            // Video and audio are handed to another app rather than shown, so
            // the block is a card, not a broken image.
            MdBlock.Attachment(id(), node.target, node.alias ?: node.target.substringAfterLast('/'))
        }
    }

    private fun fence(node: FencedCodeBlock): MdBlock {
        val language =
            node.info
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.substringBefore(' ')
        return when (language?.lowercase()) {
            "mermaid" -> {
                hasMermaid = true
                MdBlock.Mermaid(id(), node.literal.trim())
            }
            // A reader gains nothing from the query source, and showing it
            // reads as a bug rather than as an unsupported feature.
            "dataview", "dataviewjs" -> MdBlock.Unsupported(id(), "Dataview query")
            "tasks" -> MdBlock.Unsupported(id(), "Tasks query")
            "compressed-json" -> MdBlock.Unsupported(id(), "Excalidraw drawing")
            else -> {
                plain.append(node.literal)
                MdBlock.CodeBlock(id(), language, node.literal.trimEnd('\n'))
            }
        }
    }

    private fun callout(node: CalloutNode): MdBlock {
        val self = id()
        val children = node.children().flatMap { blocksFor(it) }
        plain.append(node.titleText).append('\n')
        return MdBlock.Callout(
            id = self,
            kind = node.kind,
            title = if (node.titleText.isEmpty()) emptyList() else listOf(MdInline.Text(node.titleText)),
            children = children,
            collapsed = node.collapsed,
            direction = TextDirection.of(node.titleText),
        )
    }

    private fun quote(node: BlockQuote): MdBlock {
        val self = id()
        val children = node.children().flatMap { blocksFor(it) }
        return MdBlock.Quote(self, children)
    }

    private fun list(
        node: Node,
        ordered: Boolean,
        start: Int,
    ): MdBlock {
        val self = id()
        val items =
            node.children().filterIsInstance<ListItem>().map { item ->
                val marker = findTaskMarker(item)
                var state =
                    when {
                        marker == null -> TaskState.NONE
                        marker.isChecked -> TaskState.CHECKED
                        else -> TaskState.UNCHECKED
                    }

                // The GFM extension only knows `[ ]` and `[x]`. Obsidian's
                // cancelled and in-progress markers arrive as literal text.
                val custom = stripCustomMarker(item)
                if (custom != null) state = custom

                val meta = extractTaskMeta(item)
                MdListItem(
                    blocks = item.children().flatMap { blocksFor(it) },
                    task = state,
                    taskMeta = meta,
                )
            }
        return MdBlock.ListBlock(self, ordered, start, items)
    }

    private fun findTaskMarker(item: ListItem): TaskListItemMarker? =
        item.children().filterIsInstance<TaskListItemMarker>().firstOrNull()

    /** Rewrites a leading `[-]` / `[/]` into a state and removes it from the text. */
    private fun stripCustomMarker(item: ListItem): TaskState? {
        val paragraph = item.children().filterIsInstance<Paragraph>().firstOrNull() ?: return null
        val text = paragraph.firstChild as? Text ?: return null
        val literal = text.literal
        val state =
            when {
                literal.startsWith("[-] ") -> TaskState.CANCELLED
                literal.startsWith("[/] ") -> TaskState.IN_PROGRESS
                else -> return null
            }
        text.literal = literal.removeRange(0, "[-] ".length)
        return state
    }

    private fun extractTaskMeta(item: ListItem): List<TaskMeta> {
        val paragraph = item.children().filterIsInstance<Paragraph>().firstOrNull() ?: return emptyList()
        val last = lastTextNode(paragraph) ?: return emptyList()
        val split = TaskMetadata.split(last.literal)
        if (split.meta.isEmpty()) return emptyList()
        last.literal = split.text
        return split.meta
    }

    private fun lastTextNode(node: Node): Text? {
        var found: Text? = null
        var child = node.firstChild
        while (child != null) {
            if (child is Text) found = child
            child = child.next
        }
        return found
    }

    private fun table(node: TableBlock): MdBlock {
        val self = id()
        val header = mutableListOf<List<MdInline>>()
        val alignments = mutableListOf<MdAlign>()
        val rows = mutableListOf<List<List<MdInline>>>()

        node.children().forEach { section ->
            when (section) {
                is TableHead ->
                    section.children().filterIsInstance<TableRow>().firstOrNull()?.let { row ->
                        row.children().filterIsInstance<TableCell>().forEach { cell ->
                            header += inlines(cell)
                            alignments += alignment(cell)
                        }
                    }

                is TableBody ->
                    section.children().filterIsInstance<TableRow>().forEach { row ->
                        rows += row.children().filterIsInstance<TableCell>().map { inlines(it) }
                    }
            }
        }

        val text = plainTextOf(node)
        plain.append(text).append('\n')
        return MdBlock.Table(self, header, rows, alignments, TextDirection.of(text))
    }

    private fun alignment(cell: TableCell): MdAlign =
        when (cell.alignment) {
            TableCell.Alignment.CENTER -> MdAlign.CENTER
            TableCell.Alignment.RIGHT -> MdAlign.END
            else -> MdAlign.START
        }

    private fun frontMatter(node: YamlFrontMatterBlock): MdBlock {
        node.children().filterIsInstance<YamlFrontMatterNode>().forEach {
            frontMatter[it.key] = it.values.joinToString(", ")
        }
        return MdBlock.FrontMatter(id(), frontMatter.toMap())
    }

    private fun htmlBlock(node: HtmlBlock): List<MdBlock> {
        val text = node.literal.replace(HTML_TAG, "").trim()
        if (text.isEmpty()) return emptyList()
        plain.append(text).append('\n')
        return listOf(MdBlock.Paragraph(id(), listOf(MdInline.Text(text)), TextDirection.of(text)))
    }

    // -- inline conversion ------------------------------------------------

    private fun inlines(parent: Node): List<MdInline> {
        val out = mutableListOf<MdInline>()
        var child = parent.firstChild
        while (child != null) {
            inline(child)?.let { out += it }
            child = child.next
        }
        return out
    }

    private fun inline(node: Node): MdInline? =
        when (node) {
            is Text -> MdInline.Text(node.literal)
            is Code -> MdInline.Code(node.literal)
            is Emphasis -> MdInline.Emphasis(inlines(node))
            is StrongEmphasis -> MdInline.Strong(inlines(node))
            is Strikethrough -> MdInline.Strikethrough(inlines(node))
            is HighlightNode -> MdInline.Highlight(inlines(node))
            is SoftLineBreak -> MdInline.SoftBreak
            is HardLineBreak -> MdInline.LineBreak
            is InlineMathNode -> {
                hasMath = true
                MdInline.InlineMath(node.latex)
            }

            is WikiLinkNode -> {
                links +=
                    ParsedLink(
                        kind = if (node.embed) LinkKind.WIKI_EMBED else LinkKind.WIKILINK,
                        rawTarget = node.target,
                        alias = node.alias,
                        heading = node.heading,
                    )
                MdInline.WikiLink(node.target, node.heading, node.alias)
            }

            is Link -> {
                val external = node.destination.startsWith("http")
                links +=
                    ParsedLink(
                        if (external) LinkKind.EXTERNAL else LinkKind.MARKDOWN,
                        node.destination,
                    )
                MdInline.Link(node.destination, inlines(node))
            }

            is Image -> MdInline.Link(node.destination, inlines(node))
            // `<br>` in prose is a line break; every other tag is dropped
            // rather than shown as source.
            is HtmlInline -> if (BR.matches(node.literal)) MdInline.LineBreak else null
            is TaskListItemMarker -> null
            else -> null
        }

    private fun plainTextOf(node: Node): String =
        buildString {
            fun walk(current: Node) {
                when (current) {
                    is Text -> append(current.literal)
                    is Code -> append(current.literal)
                    is SoftLineBreak, is HardLineBreak -> append(' ')
                    is WikiLinkNode -> append(current.alias ?: current.target)
                    is InlineMathNode -> append(current.latex)
                    else -> Unit
                }
                var child = current.firstChild
                while (child != null) {
                    walk(child)
                    child = child.next
                }
            }
            var child = node.firstChild
            while (child != null) {
                walk(child)
                child = child.next
            }
        }.trim()

    private companion object {
        val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val HTML_TAG = Regex("""<[^>]+>""")
        val RENDERABLE_IMAGES = setOf("jpg", "jpeg", "png", "gif", "svg", "webp")
    }
}

internal fun Node.children(): List<Node> {
    val out = mutableListOf<Node>()
    var child = firstChild
    while (child != null) {
        out += child
        child = child.next
    }
    return out
}
