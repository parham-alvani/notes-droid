package me.parham1995.notes.markdown

import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Node

/** `[[target#heading|alias]]`, or `![[target]]` when [embed] is set. */
class WikiLinkNode(
    val target: String,
    val heading: String?,
    val alias: String?,
    val embed: Boolean,
) : CustomNode()

/** Obsidian's `==highlight==`. */
class HighlightNode : CustomNode()

class InlineMathNode(
    val latex: String,
) : CustomNode()

class DisplayMathNode(
    val latex: String,
) : CustomNode()

/**
 * A blockquote that opened with `> [!type]`. Obsidian callouts *are*
 * blockquotes, so this is produced by rewriting the quote after parsing rather
 * than by a block parser of its own.
 */
class CalloutNode(
    val kind: CalloutKind,
    val titleText: String,
    val collapsed: Boolean,
    /** `+` or `-` after the type. */
    val foldable: Boolean = collapsed,
    /** The type as written, lowercased. */
    val type: String = kind.name.lowercase(),
    /**
     * The first line's inline nodes -- links, code, maths -- held apart from
     * the tree, because the title is not part of the callout's body.
     */
    val title: Node = CalloutTitleNode(),
) : CustomBlock()

/** A detached container for a callout title's inline nodes. */
class CalloutTitleNode : CustomNode()

/** An inline `#tag` or `#parent/child`, without its `#`. */
class TagNode(
    val name: String,
) : CustomNode()
