package me.parham1995.notes.markdown

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.front.matter.YamlFrontMatterExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Builds the one configured parser the app uses.
 *
 * [Parser] is thread-safe for parsing, so a single instance is shared and the
 * indexer can parse notes in parallel.
 */
object MarkdownParser {
    val instance: Parser by lazy { build() }

    private fun build(): Parser =
        Parser
            .builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create(),
                    YamlFrontMatterExtension.create(),
                    AutolinkExtension.create(),
                    WikiLinkExtension.create(),
                    HighlightExtension.create(),
                    MathExtension.create(),
                ),
            )
            // Indented code blocks are off, exactly as Obsidian has them. Every
            // code block in a real vault is fenced, while four-space-indented
            // lines are lazy list continuations -- CommonMark would turn those
            // into code and mangle them.
            .enabledBlockTypes(
                setOf(
                    Heading::class.java,
                    FencedCodeBlock::class.java,
                    HtmlBlock::class.java,
                    ThematicBreak::class.java,
                    BlockQuote::class.java,
                    ListBlock::class.java,
                ),
            ).postProcessor(CalloutPostProcessor())
            .build()

    fun parse(markdown: String): Node = instance.parse(markdown)

    /**
     * Markdown straight to the render model. This is the entry point callers
     * outside this module use, so commonmark stays an implementation detail
     * rather than leaking into every consumer's classpath.
     */
    fun parseNote(
        markdown: String,
        imageResolver: (String) -> String = { it },
    ): ParsedNote = BlockFlattener(imageResolver).flatten(instance.parse(markdown))
}
