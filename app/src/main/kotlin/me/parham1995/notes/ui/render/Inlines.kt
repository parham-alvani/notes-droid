package me.parham1995.notes.ui.render

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import me.parham1995.notes.markdown.HighlightColor
import me.parham1995.notes.markdown.MarkdownLinks
import me.parham1995.notes.markdown.MdInline
import me.parham1995.notes.ui.theme.Naz
import me.parham1995.notes.ui.theme.markup

/** How a tapped link should be handled, decided by the screen rather than here. */
data class InlineActions(
    val onWikiLink: (target: String, heading: String?) -> Unit = { _, _ -> },
    val onExternalLink: (url: String) -> Unit = {},
    /**
     * `[text](destination)` where the destination has no scheme -- another
     * note, a heading, a file. Handed over raw; deciding what it names is the
     * screen's job, because only the screen knows the note it is in.
     */
    val onInternalLink: (destination: String) -> Unit = {},
    /**
     * A link already resolved to the note it names.
     *
     * Separate from [onWikiLink] because a link inside an embedded note is
     * resolved against *that* note's links, not the one on screen: the two
     * rarely link to the same things, and looking a target up in the wrong
     * note's table reports a working link as broken.
     */
    val onNoteLink: (noteId: Long, heading: String?) -> Unit = { _, _ -> },
    /** A link that resolves to nothing, said plainly rather than ignored. */
    val onBrokenLink: (target: String) -> Unit = {},
    /** A `#tag`, by its name without the `#`: list the notes carrying it. */
    val onTag: (tag: String) -> Unit = {},
    /** A footnote's number, by the label it was written with: show what it says. */
    val onFootnote: (label: String) -> Unit = {},
    /**
     * A word a finger was held on: look it up. Null where holding text should
     * only select it -- everywhere but the reader.
     */
    val onDefine: ((word: String) -> Unit)? = null,
)

/**
 * Turns inline content into an [AnnotatedString].
 *
 * One string per block, never per document. Links become [LinkAnnotation]s so
 * tapping and text selection can coexist -- a tap handler on the whole block
 * would fight the selection gesture.
 *
 * Maths is the exception: it cannot be a span, so each formula is emitted as an
 * inline placeholder and drawn by [mathInlineContent].
 */
@Composable
fun List<MdInline>.toAnnotated(
    actions: InlineActions = InlineActions(),
    brokenLinks: Set<String> = emptySet(),
): Pair<AnnotatedString, List<String>> = annotate(this, MaterialTheme.colorScheme, actions, brokenLinks)

/**
 * The same, outside composition, so a caller can remember the result.
 *
 * Building the string walks every inline and allocates every span; doing it on
 * each recomposition -- which a keystroke in the find bar used to cause for
 * every block on screen -- is work thrown away the moment it is done.
 */
internal fun annotate(
    inlines: List<MdInline>,
    colors: ColorScheme,
    actions: InlineActions,
    brokenLinks: Set<String>,
): Pair<AnnotatedString, List<String>> {
    val formulas = mutableListOf<String>()

    val text =
        buildAnnotatedString {
            appendInlines(this, inlines, colors, actions, brokenLinks, formulas)
        }
    return text to formulas
}

/**
 * [marked] is true inside a highlight: there bold, italic, strike, code and
 * links keep their weight, slant and lines but take the highlight's text
 * colour, because their own colours were picked for the page, not for the
 * highlight's fill, and did not read on it.
 */
private fun appendInlines(
    builder: AnnotatedString.Builder,
    nodes: List<MdInline>,
    colors: ColorScheme,
    actions: InlineActions,
    brokenLinks: Set<String>,
    formulas: MutableList<String>,
    marked: Boolean = false,
) {
    fun own(color: Color): Color = if (marked) Color.Unspecified else color

    nodes.forEach { node ->
        when (node) {
            is MdInline.Text -> builder.append(node.text)

            // @markup.raw
            is MdInline.Code ->
                builder.withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colors.surfaceVariant,
                        color = own(colors.markup.Raw),
                        fontStyle = FontStyle.Italic,
                    ),
                ) { append(node.code) }

            // @markup.italic
            is MdInline.Emphasis ->
                builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = own(colors.markup.Italic))) {
                    appendInlines(builder, node.children, colors, actions, brokenLinks, formulas, marked)
                }

            // @markup.strong
            is MdInline.Strong ->
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = own(colors.markup.Strong))) {
                    appendInlines(builder, node.children, colors, actions, brokenLinks, formulas, marked)
                }

            // @markup.strike
            is MdInline.Strikethrough ->
                builder.withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough, color = own(colors.markup.Strike)),
                ) {
                    appendInlines(builder, node.children, colors, actions, brokenLinks, formulas, marked)
                }

            is MdInline.Highlight ->
                builder.withStyle(highlightStyle(node.color, colors)) {
                    appendInlines(builder, node.children, colors, actions, brokenLinks, formulas, marked = true)
                }

            is MdInline.Link -> {
                val link =
                    LinkAnnotation.Clickable(
                        tag = node.destination,
                        styles =
                            TextLinkStyles(
                                SpanStyle(color = own(colors.primary), textDecoration = TextDecoration.Underline),
                            ),
                    ) {
                        // Only a scheme makes it the web. Everything else was
                        // handed to the system as a URL too, and opened nothing.
                        if (MarkdownLinks.isExternal(node.destination)) {
                            actions.onExternalLink(node.destination)
                        } else {
                            actions.onInternalLink(node.destination)
                        }
                    }
                builder.withLink(link) {
                    appendInlines(builder, node.children, colors, actions, brokenLinks, formulas, marked)
                }
            }

            is MdInline.WikiLink -> {
                val broken = node.target.isNotEmpty() && node.target in brokenLinks
                val style =
                    if (broken) {
                        // Shown, not hidden: many broken links are deliberate
                        // placeholders in index notes, and seeing them is the
                        // point.
                        SpanStyle(color = colors.outline, textDecoration = TextDecoration.LineThrough)
                    } else {
                        // @markup.link.label; underlined on a highlight, where
                        // it takes the highlight's colour.
                        if (marked) {
                            SpanStyle(textDecoration = TextDecoration.Underline)
                        } else {
                            SpanStyle(color = colors.markup.Link)
                        }
                    }
                val link =
                    LinkAnnotation.Clickable(tag = node.target, styles = TextLinkStyles(style)) {
                        actions.onWikiLink(node.target, node.heading)
                    }
                builder.withLink(link) { builder.append(node.display) }
            }

            // Drawn as a tag, and a way to every other note carrying it --
            // which is what a tag is for, and all plain text could not do.
            is MdInline.Tag -> {
                val link =
                    LinkAnnotation.Clickable(
                        tag = TAG_PREFIX + node.name,
                        styles =
                            TextLinkStyles(
                                SpanStyle(color = colors.onSecondaryContainer, background = colors.secondaryContainer),
                            ),
                    ) { actions.onTag(node.name) }
                builder.withLink(link) { builder.append('#').append(node.name) }
            }

            // A number set small and high, the way a footnote is read; the
            // note itself is a tap away rather than a scroll to the bottom.
            is MdInline.FootnoteRef -> {
                val link =
                    LinkAnnotation.Clickable(
                        tag = FOOTNOTE_PREFIX + node.label,
                        styles =
                            TextLinkStyles(
                                SpanStyle(
                                    color = colors.primary,
                                    fontSize = 0.75.em,
                                    baselineShift = BaselineShift.Superscript,
                                ),
                            ),
                    ) { actions.onFootnote(node.label) }
                builder.withLink(link) { builder.append(node.number.toString()) }
            }

            is MdInline.InlineMath -> {
                val index = formulas.size
                formulas += node.latex
                // The placeholder is what inlineContent hangs the rendered
                // formula on; the character itself is never drawn.
                builder.appendInlineContent(MATH_TAG_PREFIX + index, node.latex)
            }

            MdInline.LineBreak -> builder.append('\n')
            MdInline.SoftBreak -> builder.append(' ')
        }
    }
}

internal const val MATH_TAG_PREFIX = "math:"
private const val TAG_PREFIX = "tag:"
private const val FOOTNOTE_PREFIX = "footnote:"

/**
 * The plain highlight keeps the theme's container colours. A coloured one is
 * naz's colour laid thinly under text left as it was, the way Obsidian washes
 * its highlights -- a translucent fill reads on the dark page and the light one
 * alike, where a solid one would need a text colour picked for each.
 */
private fun highlightStyle(
    color: HighlightColor?,
    colors: ColorScheme,
): SpanStyle {
    val fill =
        when (color) {
            null -> return SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer)
            HighlightColor.RED -> Naz.Red
            HighlightColor.ORANGE -> Naz.Orange
            HighlightColor.YELLOW -> Naz.VividYellow
            HighlightColor.GREEN -> Naz.SpringGreen
            HighlightColor.BLUE -> Naz.Blue
            HighlightColor.PURPLE -> Naz.Purple
        }
    return SpanStyle(background = fill.copy(alpha = HIGHLIGHT_WASH))
}

private const val HIGHLIGHT_WASH = 0.35f
