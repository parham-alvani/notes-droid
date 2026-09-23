package me.parham1995.notes.markdown

/**
 * Pulls every link out of the finished block list, each with the text around
 * it.
 *
 * Doing this over blocks rather than during parsing is what makes the context
 * right: a backlink is only useful if it shows the sentence the link sits in,
 * and that sentence is only knowable once the block it belongs to exists. The
 * context is then stored on the link row, so rendering backlinks never has to
 * re-read or re-parse the source note.
 */
object LinkExtraction {
    private const val MAX_CONTEXT = 240

    fun from(blocks: List<MdBlock>): List<ParsedLink> {
        val out = mutableListOf<ParsedLink>()
        blocks.forEach { collect(it, out) }
        return out
    }

    private fun collect(
        block: MdBlock,
        out: MutableList<ParsedLink>,
    ) {
        when (block) {
            is MdBlock.Paragraph -> inlines(block.inlines, context(block.inlines), out)
            is MdBlock.Heading -> inlines(block.inlines, block.text, out)
            is MdBlock.Quote -> block.children.forEach { collect(it, out) }
            is MdBlock.Callout -> {
                inlines(block.title, context(block.title), out)
                block.children.forEach { collect(it, out) }
            }

            is MdBlock.ListBlock -> block.items.forEach { item -> item.blocks.forEach { collect(it, out) } }
            is MdBlock.Table -> {
                (block.header + block.rows.flatten()).forEach { cell ->
                    inlines(cell, context(cell), out)
                }
            }

            is MdBlock.Image -> out += ParsedLink(LinkKind.IMAGE, block.path, block.alt, context = block.alt.orEmpty())
            is MdBlock.Attachment -> out += ParsedLink(LinkKind.WIKI_EMBED, block.path, block.label)
            is MdBlock.NoteEmbed ->
                out += ParsedLink(LinkKind.WIKI_EMBED, block.target, block.label, heading = block.heading)
            else -> Unit
        }
    }

    private fun inlines(
        nodes: List<MdInline>,
        context: String,
        out: MutableList<ParsedLink>,
    ) {
        nodes.forEach { node ->
            when (node) {
                is MdInline.WikiLink ->
                    out +=
                        ParsedLink(
                            kind = LinkKind.WIKILINK,
                            rawTarget = node.target,
                            alias = node.alias,
                            heading = node.heading,
                            context = context,
                        )

                is MdInline.Link ->
                    out +=
                        ParsedLink(
                            kind = if (node.destination.startsWith("http")) LinkKind.EXTERNAL else LinkKind.MARKDOWN,
                            rawTarget = node.destination,
                            context = context,
                        )

                is MdInline.Emphasis -> inlines(node.children, context, out)
                is MdInline.Strong -> inlines(node.children, context, out)
                is MdInline.Strikethrough -> inlines(node.children, context, out)
                is MdInline.Highlight -> inlines(node.children, context, out)
                else -> Unit
            }
        }
    }

    private fun context(nodes: List<MdInline>): String = plainText(nodes).trim().take(MAX_CONTEXT)
}
