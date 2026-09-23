package me.parham1995.notes.markdown

/**
 * The render model: a document reduced to a flat list of blocks, each carrying
 * its own inline content.
 *
 * Android-free on purpose. Everything here can be built, tested and reasoned
 * about on the JVM, and the UI layer turns it into Compose types. The flatness
 * is the important part -- a note becomes a `LazyColumn` over these, so a 139KB
 * note composes only what is on screen instead of building one enormous
 * `AnnotatedString`.
 */
sealed interface MdInline {
    data class Text(
        val text: String,
    ) : MdInline

    data class Code(
        val code: String,
    ) : MdInline

    data class Emphasis(
        val children: List<MdInline>,
    ) : MdInline

    data class Strong(
        val children: List<MdInline>,
    ) : MdInline

    data class Strikethrough(
        val children: List<MdInline>,
    ) : MdInline

    /** Obsidian's `==highlight==`. */
    data class Highlight(
        val children: List<MdInline>,
    ) : MdInline

    data class Link(
        val destination: String,
        val children: List<MdInline>,
    ) : MdInline

    /**
     * `[[target|alias]]` or `[[target#heading]]`. [targetPath] is filled in by
     * the resolver; null means the link is broken and should be styled as such.
     */
    data class WikiLink(
        val target: String,
        val heading: String? = null,
        val alias: String? = null,
        val targetPath: String? = null,
    ) : MdInline {
        /** What the reader sees. Obsidian shows the raw target when no alias. */
        val display: String
            get() =
                alias ?: buildString {
                    append(target.substringAfterLast('/'))
                    heading?.let { append(" › ").append(it) }
                }

        val isBroken: Boolean get() = targetPath == null && target.isNotEmpty()

        /** `[[#Heading]]` -- a jump inside the current note. */
        val isSameFile: Boolean get() = target.isEmpty()
    }

    data class InlineMath(
        val latex: String,
    ) : MdInline

    /** A hard break: two trailing spaces, a backslash, or a prose `<br>`. */
    data object LineBreak : MdInline

    data object SoftBreak : MdInline
}

enum class CalloutKind {
    NOTE,
    ABSTRACT,
    INFO,
    TODO,
    TIP,
    SUCCESS,
    QUESTION,
    WARNING,
    FAILURE,
    DANGER,
    BUG,
    EXAMPLE,
    QUOTE,
    FIGURE,
    READ,
    ;

    companion object {
        /**
         * Obsidian accepts several aliases per kind. Anything unrecognised
         * falls back to [NOTE] rather than losing the callout, which is what
         * Obsidian itself does.
         */
        private val ALIASES =
            mapOf(
                "note" to NOTE,
                "abstract" to ABSTRACT,
                "summary" to ABSTRACT,
                "tldr" to ABSTRACT,
                "info" to INFO,
                "todo" to TODO,
                "tip" to TIP,
                "hint" to TIP,
                "important" to TIP,
                "success" to SUCCESS,
                "check" to SUCCESS,
                "done" to SUCCESS,
                "question" to QUESTION,
                "help" to QUESTION,
                "faq" to QUESTION,
                "warning" to WARNING,
                "caution" to WARNING,
                "attention" to WARNING,
                "failure" to FAILURE,
                "fail" to FAILURE,
                "missing" to FAILURE,
                "danger" to DANGER,
                "error" to DANGER,
                "bug" to BUG,
                "example" to EXAMPLE,
                "quote" to QUOTE,
                "cite" to QUOTE,
                // Two custom kinds defined by this vault's CSS snippet.
                "figure" to FIGURE,
                "read" to READ,
            )

        fun parse(token: String): CalloutKind = ALIASES[token.lowercase()] ?: NOTE
    }
}

enum class MdAlign { START, CENTER, END }

/** Obsidian Tasks adds two states GFM does not have. */
enum class TaskState { UNCHECKED, CHECKED, CANCELLED, IN_PROGRESS, NONE }

/**
 * One trailing emoji-date pair from the Obsidian Tasks plugin. Rendered as a
 * chip; left in the text it is just noise.
 */
data class TaskMeta(
    val emoji: String,
    val value: String,
)

data class MdListItem(
    val blocks: List<MdBlock>,
    val task: TaskState = TaskState.NONE,
    val taskMeta: List<TaskMeta> = emptyList(),
    /**
     * The zero-based line this item started on in the source, or -1 when the
     * parser was not asked for spans.
     *
     * Only tasks use it, and only to find the line again in the file so it can
     * be ticked. Everything else about a list item is content.
     */
    val line: Int = -1,
)

/** Text direction, detected per block from its first strong character. */
enum class MdDirection { LTR, RTL }

sealed interface MdBlock {
    /** Stable within one document, so `LazyColumn` keys survive recomposition. */
    val id: Int

    val direction: MdDirection get() = MdDirection.LTR

    data class Heading(
        override val id: Int,
        val level: Int,
        val inlines: List<MdInline>,
        val text: String,
        override val direction: MdDirection = MdDirection.LTR,
    ) : MdBlock

    data class Paragraph(
        override val id: Int,
        val inlines: List<MdInline>,
        override val direction: MdDirection = MdDirection.LTR,
    ) : MdBlock

    data class CodeBlock(
        override val id: Int,
        val language: String?,
        val code: String,
    ) : MdBlock

    /** A ```mermaid fence, lifted out so it can be rendered rather than shown. */
    data class Mermaid(
        override val id: Int,
        val code: String,
    ) : MdBlock

    data class MathBlock(
        override val id: Int,
        val latex: String,
    ) : MdBlock

    data class Quote(
        override val id: Int,
        val children: List<MdBlock>,
        override val direction: MdDirection = MdDirection.LTR,
    ) : MdBlock

    data class Callout(
        override val id: Int,
        val kind: CalloutKind,
        val title: List<MdInline>,
        val children: List<MdBlock>,
        val collapsed: Boolean = false,
        override val direction: MdDirection = MdDirection.LTR,
    ) : MdBlock

    data class ListBlock(
        override val id: Int,
        val ordered: Boolean,
        val start: Int,
        val items: List<MdListItem>,
        override val direction: MdDirection = MdDirection.LTR,
    ) : MdBlock

    data class Table(
        override val id: Int,
        val header: List<List<MdInline>>,
        val rows: List<List<List<MdInline>>>,
        val alignments: List<MdAlign>,
        override val direction: MdDirection = MdDirection.LTR,
    ) : MdBlock

    /**
     * `![[uploads/x.jpg]]` or `![alt](../uploads/x.jpg)`, already resolved.
     *
     * [width] and [height] are the size Obsidian reads from after the pipe --
     * `![[x.jpg|300]]`, `![[x.jpg|300x200]]` -- in its own CSS pixels, which is
     * to say density-independent ones.
     */
    data class Image(
        override val id: Int,
        val path: String,
        val alt: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : MdBlock

    /** An embed pointing at something that cannot be shown inline. */
    data class Attachment(
        override val id: Int,
        val path: String,
        val label: String,
    ) : MdBlock

    /**
     * `![[Another note]]` or `![[Another note#Heading]]`: another note, or one
     * section of it, drawn in place.
     *
     * [target] is the raw link target, resolved the way a wikilink is -- it is
     * a note name, not a file path.
     */
    data class NoteEmbed(
        override val id: Int,
        val target: String,
        val heading: String?,
        val label: String,
    ) : MdBlock

    data class ThematicBreak(
        override val id: Int,
    ) : MdBlock

    /**
     * A block deliberately not rendered -- a `dataview` or `tasks` query, or an
     * Excalidraw payload. Showing the query text to a reader is worse than
     * saying plainly that it is not supported.
     */
    data class Unsupported(
        override val id: Int,
        val label: String,
    ) : MdBlock

    data class FrontMatter(
        override val id: Int,
        val values: Map<String, String>,
    ) : MdBlock
}

/** A parsed note: its blocks plus everything the index needs. */
data class ParsedNote(
    val blocks: List<MdBlock>,
    val title: String,
    val headings: List<ParsedHeading>,
    val links: List<ParsedLink>,
    val plainText: String,
    val frontMatter: Map<String, String>,
    val isRtl: Boolean,
    val hasMermaid: Boolean,
    val hasMath: Boolean,
)

data class ParsedHeading(
    val level: Int,
    val text: String,
    val slug: String,
    /** Index into [ParsedNote.blocks], so tapping an outline entry can scroll. */
    val blockIndex: Int,
)

enum class LinkKind { WIKILINK, WIKI_EMBED, MARKDOWN, IMAGE, EXTERNAL }

data class ParsedLink(
    val kind: LinkKind,
    val rawTarget: String,
    val alias: String? = null,
    val heading: String? = null,
    /** The surrounding line, stored so backlinks never re-read files. */
    val context: String = "",
)
