package me.parham1995.notes.markdown

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.InlineFootnote
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
    private var properties = emptyList<FrontMatterProperty>()
    private val inlineTags = mutableListOf<String>()

    /** Footnote labels in the order they are first referred to, which numbers them. */
    private val footnoteOrder = mutableListOf<String>()
    private val definitions = HashMap<String, FootnoteDefinition>()
    private val inlineFootnotes = HashMap<String, List<MdBlock>>()

    /**
     * `^block-id` markers, each with the id of the block it names -- an id, to
     * be turned into a position once the list is final -- and what embedding
     * it should draw.
     */
    private val blockAnchors = LinkedHashMap<String, Int>()
    private val blockTargets = HashMap<String, MdBlock>()

    /** A `^id` written on a line of its own, waiting for the block before it. */
    private var orphanRef: String? = null

    /** The source, when it is to hand, is what front matter is read from. */
    private var source: String? = null

    fun flatten(
        document: Node,
        source: String? = null,
    ): ParsedNote {
        this.source = source
        val blocks = blocksOf(document.children()).toMutableList()
        footnotes()?.let { blocks += it }

        val plainText = plain.toString().trim()
        return ParsedNote(
            blocks = blocks,
            title =
                headings.firstOrNull { it.level == 1 }?.text
                    ?: headings.firstOrNull()?.text.orEmpty(),
            headings = positioned(headings, blocks),
            links = LinkExtraction.from(blocks),
            plainText = plainText,
            frontMatter = frontMatter.toMap(),
            isRtl = TextDirection.containsRtl(plainText),
            hasMermaid = hasMermaid,
            hasMath = hasMath,
            tags = Tags.distinct(Tags.fromFrontMatter(properties) + inlineTags),
            aliases = FrontMatter.aliases(properties),
            blockRefs = blockAnchors.mapValues { (_, id) -> positionOf(id, blocks) },
            blockTargets = blockTargets.toMap(),
        )
    }

    /**
     * Blocks for a run of sibling nodes, with a `^id` written on a line of its
     * own given to the block before it -- which is what Obsidian does with one
     * under a table, a quote or a list, where there is no line to end with it.
     */
    private fun blocksOf(nodes: List<Node>): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        nodes.forEach { node ->
            val made = blocksFor(node)
            orphanRef?.let { ref ->
                orphanRef = null
                out.lastOrNull()?.let { record(ref, it) }
            }
            out += made
        }
        return out
    }

    private fun record(
        ref: String,
        block: MdBlock,
        target: MdBlock = block,
    ) {
        if (ref in blockAnchors) return
        blockAnchors[ref] = block.id
        blockTargets[ref] = target
    }

    /**
     * Takes a trailing ` ^block-id` off the last text of [node] and returns
     * the id, or null when it does not end with one.
     */
    private fun stripBlockId(node: Node): String? {
        val last = node.lastChild as? Text ?: return null
        val match = BLOCK_ID.find(last.literal) ?: return null
        val rest = last.literal.substring(0, match.range.first).trimEnd()
        if (rest.isEmpty()) last.unlink() else last.literal = rest
        return match.groupValues[1]
    }

    private fun id() = nextId++

    private fun footnoteNumber(label: String): Int {
        val at = footnoteOrder.indexOf(label)
        if (at >= 0) return at + 1
        footnoteOrder += label
        return footnoteOrder.size
    }

    /**
     * The footnotes block, or null when nothing refers to one.
     *
     * Built last, in the order of first reference, because a definition may
     * be written anywhere -- before the text that cites it, or in the middle
     * of a list. A definition nothing cites is left out, as Obsidian leaves it
     * out of reading view. Converting one can cite another, so the list is
     * walked by index while it grows.
     */
    private fun footnotes(): MdBlock.Footnotes? {
        if (footnoteOrder.isEmpty()) return null
        val entries = mutableListOf<FootnoteEntry>()
        var index = 0
        while (index < footnoteOrder.size) {
            val label = footnoteOrder[index]
            val content =
                inlineFootnotes[label]
                    ?: definitions[label]?.children()?.let { blocksOf(it) }
                    ?: emptyList()
            entries += FootnoteEntry(label, index + 1, content)
            index++
        }
        return MdBlock.Footnotes(id(), entries)
    }

    /**
     * Turns each heading's block *id* into its position in the rendered list.
     *
     * Ids are handed out to nested blocks too -- every list item, every line
     * inside a callout -- so an id runs ahead of the position as soon as a
     * note contains a list. Recording the id and scrolling to it as though it
     * were a position sent the outline, and every `[[Note#Heading]]` link,
     * somewhere else or off the end. Both were written against this and
     * neither has ever worked.
     *
     * A heading inside a callout or a quote is not a block of the list at all,
     * so it takes the position of whatever contains it -- which is where it is
     * on screen.
     */
    private fun positioned(
        found: List<ParsedHeading>,
        blocks: List<MdBlock>,
    ): List<ParsedHeading> = found.map { heading -> heading.copy(blockIndex = positionOf(heading.blockIndex, blocks)) }

    /** Where the block with [id] is on screen: itself, or whatever contains it. */
    private fun positionOf(
        id: Int,
        blocks: List<MdBlock>,
    ): Int {
        val exact = blocks.indexOfFirst { it.id == id }
        if (exact >= 0) return exact
        return blocks.indexOfLast { it.id <= id }.coerceAtLeast(0)
    }

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
            // Gathered, and drawn at the end with the others.
            is FootnoteDefinition -> {
                definitions.putIfAbsent(node.label, node)
                emptyList()
            }
            else -> emptyList()
        }

    private fun heading(node: Heading): MdBlock.Heading {
        val ref = stripBlockId(node)
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
        ref?.let { record(it, block) }
        return block
    }

    /**
     * A paragraph, split around anything in it that has to be a block.
     *
     * A display formula or a single embed on its own is promoted to a block of
     * its own, so it can be centred, zoomed and cached rather than squeezed
     * into a line of text. Written straight under a line of prose -- which is
     * how they are usually written -- they are part of that paragraph as far
     * as Markdown is concerned, so the paragraph is cut around them: the text
     * before, the block, the text after. Promoting only a lone one dropped a
     * formula with a sentence above it without trace.
     */
    private fun paragraph(node: Paragraph): List<MdBlock> {
        // Obsidian's `^block-id` is an address, not words: it comes off the
        // end of the text and is remembered against the block it ends.
        val ref = stripBlockId(node)
        val out = mutableListOf<MdBlock>()
        val run = mutableListOf<Node>()

        fun flush() {
            val trimmed = run.dropWhile { it.isBreak() }.dropLastWhile { it.isBreak() }
            run.clear()
            if (trimmed.isEmpty() || trimmed.all { it.isBreak() || (it is Text && it.literal.isBlank()) }) return
            val text = plainTextOf(trimmed)
            plain.append(text).append('\n')
            out += MdBlock.Paragraph(id(), inlinesOf(trimmed), TextDirection.of(text))
        }

        node.children().forEach { child ->
            if (isBreakout(child)) {
                flush()
                out += breakout(child)
            } else {
                run += child
            }
        }
        flush()
        if (ref != null) {
            // A line that was nothing but the marker names the block before it.
            if (out.isEmpty()) orphanRef = ref else record(ref, out.last())
        }
        return out
    }

    private fun Node.isBreak(): Boolean = this is SoftLineBreak || this is HardLineBreak

    /**
     * Whether [node] is a block that happens to have been written inside a
     * paragraph.
     *
     * Embeds and images are among them, and not only when alone: two
     * `![[image]]` lines, or an image with its caption on the line under it,
     * are one paragraph, and only a lone embed used to become an image -- the
     * rest were shown as the link text they were written as. An image inside a
     * sentence is lifted out of it too; a picture cannot be a run of text, and
     * a link standing in for one is worse than the sentence being cut.
     */
    private fun isBreakout(node: Node): Boolean =
        node is DisplayMathNode ||
            (node is WikiLinkNode && node.embed) ||
            node is Image

    private fun breakout(node: Node): MdBlock =
        when (node) {
            is DisplayMathNode -> {
                hasMath = true
                MdBlock.MathBlock(id(), node.latex)
            }

            is WikiLinkNode -> embed(node)

            is Image -> {
                val sized = Sized.of(plainTextOf(node))
                links += ParsedLink(LinkKind.IMAGE, node.destination, sized.alt)
                MdBlock.Image(id(), imageResolver(node.destination), sized.alt, sized.width, sized.height)
            }

            else -> error("not a breakout: $node")
        }

    private fun embed(node: WikiLinkNode): MdBlock {
        links += ParsedLink(LinkKind.WIKI_EMBED, node.target, node.alias)
        val extension = node.target.substringAfterLast('.', "").lowercase()
        return when {
            extension in RENDERABLE_IMAGES -> {
                val sized = Sized.of(node.alias.orEmpty())
                MdBlock.Image(id(), imageResolver(node.target), sized.alt, sized.width, sized.height)
            }

            // No extension, or `.md`: another note, which Obsidian draws in
            // place. Offered as an attachment it went to "another app" that
            // had nothing to open. A name like "Release v1.2 notes" has a dot
            // in it and is still a note, which is why a file extension has to
            // look like one.
            extension == "md" || !FILE_EXTENSION.matches(extension) ->
                MdBlock.NoteEmbed(
                    id = id(),
                    target = node.target,
                    heading = node.heading,
                    label = node.alias ?: node.target.substringAfterLast('/').removeSuffix(".md"),
                )

            // Video and audio are handed to another app rather than shown, so
            // the block is a card, not a broken image.
            // Resolved like an image: `![[clip.mp4]]` names a file wherever it
            // sits, and passed through raw it was looked for at the vault root.
            else ->
                MdBlock.Attachment(
                    id(),
                    imageResolver(node.target),
                    node.alias ?: node.target.substringAfterLast('/'),
                )
        }
    }

    /**
     * An image's alias, which Obsidian overloads: a trailing `300` or
     * `300x200` is a size, and whatever is left before it is the caption.
     * Read as a caption, the size ended up printed under the picture.
     */
    private class Sized(
        val alt: String?,
        val width: Int?,
        val height: Int?,
    ) {
        companion object {
            private val SIZE = Regex("""^(\d{1,5})(?:x(\d{1,5}))?$""")

            fun of(alias: String): Sized {
                val parts = alias.split('|')
                val size = SIZE.matchEntire(parts.last().trim())
                val caption = (if (size != null) parts.dropLast(1) else parts).joinToString("|").trim()
                return Sized(
                    alt = caption.takeIf { it.isNotEmpty() },
                    width = size?.groupValues?.get(1)?.toIntOrNull(),
                    height = size?.groupValues?.get(2)?.toIntOrNull(),
                )
            }
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
            "dataview", "dataviewjs" -> MdBlock.Unsupported(id(), "Dataview query", UnsupportedKind.DATAVIEW)
            "tasks" -> MdBlock.Unsupported(id(), "Tasks query", UnsupportedKind.TASKS_QUERY)
            "compressed-json" -> MdBlock.Unsupported(id(), "Excalidraw drawing", UnsupportedKind.EXCALIDRAW)
            else -> {
                plain.append(node.literal)
                MdBlock.CodeBlock(id(), language, node.literal.trimEnd('\n'))
            }
        }
    }

    private fun callout(node: CalloutNode): MdBlock {
        val self = id()
        val children = blocksOf(node.children())
        plain.append(node.titleText).append('\n')
        return MdBlock.Callout(
            id = self,
            kind = node.kind,
            title = if (node.titleText.isEmpty()) emptyList() else trimmedTitle(inlines(node.title)),
            children = children,
            collapsed = node.collapsed,
            direction = TextDirection.of(node.titleText),
            foldable = node.foldable,
            type = node.type,
        )
    }

    /** The space after `[!type]` is not part of the title. */
    private fun trimmedTitle(title: List<MdInline>): List<MdInline> {
        val first = title.firstOrNull() as? MdInline.Text ?: return title
        val trimmed = first.text.trimStart()
        return if (trimmed.isEmpty()) title.drop(1) else listOf(MdInline.Text(trimmed)) + title.drop(1)
    }

    private fun quote(node: BlockQuote): MdBlock {
        val self = id()
        val children = blocksOf(node.children())
        return MdBlock.Quote(self, children)
    }

    private fun list(
        node: Node,
        ordered: Boolean,
        start: Int,
    ): MdBlock {
        val self = id()
        val items =
            node.children().filterIsInstance<ListItem>().mapIndexed { index, item ->
                // Before the task's metadata is read: the id is written last,
                // after the dates, and has to come off first.
                val ref =
                    item
                        .children()
                        .filterIsInstance<Paragraph>()
                        .firstOrNull()
                        ?.let { stripBlockId(it) }
                val marker = findTaskMarker(item)
                var state =
                    when {
                        marker == null -> TaskState.NONE
                        marker.isChecked -> TaskState.CHECKED
                        else -> TaskState.UNCHECKED
                    }
                var status = if (marker?.isChecked == true) 'x' else ' '

                // The GFM extension only knows `[ ]` and `[x]`. Every other
                // status -- Obsidian's cancelled and in-progress, and the
                // `[>]` `[!]` `[?]` a theme gives a glyph -- arrives as text.
                if (marker == null) {
                    stripCustomMarker(item)?.let { custom ->
                        status = custom
                        state = stateOf(custom)
                    }
                }

                val meta = extractTaskMeta(item)
                val built =
                    MdListItem(
                        blocks = blocksOf(item.children()),
                        task = state,
                        taskMeta = meta,
                        line = item.sourceSpans.firstOrNull()?.lineIndex ?: -1,
                        status = status,
                    )
                // An item's id names the item, and embedding it draws that
                // item alone, as a list of one.
                if (ref != null) {
                    built.blocks.firstOrNull()?.let { first ->
                        record(ref, first, MdBlock.ListBlock(id(), ordered, start + index, listOf(built)))
                    }
                }
                built
            }
        return MdBlock.ListBlock(self, ordered, start, items)
    }

    private fun findTaskMarker(item: ListItem): TaskListItemMarker? =
        item.children().filterIsInstance<TaskListItemMarker>().firstOrNull()

    /**
     * Takes a leading `[c] ` off the item's text and returns `c`, or null when
     * the item does not start with one.
     *
     * Any single character, as the Tasks plugin has it. Only the first text
     * node is looked at, so `[see](url)` -- a link, not a status -- is never
     * mistaken for one.
     */
    private fun stripCustomMarker(item: ListItem): Char? {
        val paragraph = item.children().filterIsInstance<Paragraph>().firstOrNull() ?: return null
        val text = paragraph.firstChild as? Text ?: return null
        val match = CUSTOM_STATUS.find(text.literal) ?: return null
        text.literal = text.literal.substring(match.range.last + 1)
        return match.groupValues[1].single()
    }

    /**
     * What a status means. The Tasks plugin's rule: `x` is done, `-` is
     * cancelled, `/` is under way, and anything else it does not know is
     * still a thing to do.
     */
    private fun stateOf(status: Char): TaskState =
        when (status) {
            'x', 'X' -> TaskState.CHECKED
            '-' -> TaskState.CANCELLED
            '/' -> TaskState.IN_PROGRESS
            else -> TaskState.UNCHECKED
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
        // Read from the source when there is one: the extension's own reading
        // drops a key with a space in it and cannot tell a list from a string.
        properties =
            source?.let { FrontMatter.parse(it) }
                ?: node.children().filterIsInstance<YamlFrontMatterNode>().map {
                    FrontMatterProperty(it.key, it.values, isList = it.values.size > 1)
                }
        properties.forEach { frontMatter[it.key] = it.values.joinToString(", ") }
        return MdBlock.FrontMatter(id(), frontMatter.toMap(), properties)
    }

    private fun htmlBlock(node: HtmlBlock): List<MdBlock> {
        val text = node.literal.replace(HTML_TAG, "").trim()
        if (text.isEmpty()) return emptyList()
        plain.append(text).append('\n')
        return listOf(MdBlock.Paragraph(id(), listOf(MdInline.Text(text)), TextDirection.of(text)))
    }

    // -- inline conversion ------------------------------------------------

    private fun inlines(parent: Node): List<MdInline> = inlinesOf(parent.children())

    private fun inlinesOf(nodes: List<Node>): List<MdInline> = nodes.mapNotNull { inline(it) }

    private fun inline(node: Node): MdInline? =
        when (node) {
            is Text -> MdInline.Text(node.literal)
            is Code -> MdInline.Code(node.literal)
            is Emphasis -> MdInline.Emphasis(inlines(node))
            is StrongEmphasis -> MdInline.Strong(inlines(node))
            is Strikethrough -> MdInline.Strikethrough(inlines(node))
            is HighlightNode -> MdInline.Highlight(inlines(node))
            is TagNode -> {
                inlineTags += node.name
                MdInline.Tag(node.name)
            }

            is FootnoteReference -> MdInline.FootnoteRef(node.label, footnoteNumber(node.label))

            // Its text becomes a footnote of its own, under a label no
            // written one can have.
            is InlineFootnote -> {
                val label = INLINE_FOOTNOTE + inlineFootnotes.size
                val text = plainTextOf(node)
                plain.append(text).append('\n')
                inlineFootnotes[label] = listOf(MdBlock.Paragraph(id(), inlines(node), TextDirection.of(text)))
                MdInline.FootnoteRef(label, footnoteNumber(label))
            }
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

    private fun plainTextOf(node: Node): String = plainTextOf(node.children())

    private fun plainTextOf(nodes: List<Node>): String =
        buildString {
            fun walk(current: Node) {
                when (current) {
                    is Text -> append(current.literal)
                    is Code -> append(current.literal)
                    is SoftLineBreak, is HardLineBreak -> append(' ')
                    is WikiLinkNode -> append(current.alias ?: current.target)
                    is InlineMathNode -> append(current.latex)
                    is TagNode -> append('#').append(current.name)
                    // Not part of the sentence it is attached to; its text
                    // is recorded as a footnote of its own.
                    is InlineFootnote -> return
                    else -> Unit
                }
                var child = current.firstChild
                while (child != null) {
                    walk(child)
                    child = child.next
                }
            }
            nodes.forEach { walk(it) }
        }.trim()

    private companion object {
        val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

        /** ` ^id` at the very end: letters, digits and dashes, as Obsidian makes them. */
        val BLOCK_ID = Regex("""(?:^|\s)\^([A-Za-z0-9-]+)\s*$""")

        /** `[^...]` labels cannot contain a space, so this can never collide with one. */
        const val INLINE_FOOTNOTE = "inline "

        /** `[c] ` at the very start: one character that is not a bracket or a space. */
        val CUSTOM_STATUS = Regex("""^\[([^\]\s])] """)
        val HTML_TAG = Regex("""<[^>]+>""")
        val RENDERABLE_IMAGES = setOf("jpg", "jpeg", "png", "gif", "svg", "webp")

        /** Short and alphanumeric: `pdf`, `mp4`, `drawio`, `7z` -- not `2 notes`. */
        val FILE_EXTENSION = Regex("""[a-z0-9]{1,6}""")
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
