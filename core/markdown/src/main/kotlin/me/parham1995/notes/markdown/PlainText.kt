package me.parham1995.notes.markdown

/**
 * A run of inlines as the words in it, with the markup dropped.
 *
 * What a backlink quotes, what a search excerpt is built from, and what a link
 * preview shows are all the same question asked three times, so it is answered
 * once here.
 */
fun plainText(nodes: List<MdInline>): String =
    buildString {
        nodes.forEach { node ->
            when (node) {
                is MdInline.Text -> append(node.text)
                is MdInline.Code -> append(node.code)
                is MdInline.Emphasis -> append(plainText(node.children))
                is MdInline.Strong -> append(plainText(node.children))
                is MdInline.Strikethrough -> append(plainText(node.children))
                is MdInline.Highlight -> append(plainText(node.children))
                is MdInline.Link -> append(plainText(node.children))
                is MdInline.WikiLink -> append(node.display)
                is MdInline.InlineMath -> append(node.latex)
                is MdInline.Tag -> append('#').append(node.name)
                is MdInline.FootnoteRef -> Unit
                MdInline.LineBreak, MdInline.SoftBreak -> append(' ')
            }
        }
    }
