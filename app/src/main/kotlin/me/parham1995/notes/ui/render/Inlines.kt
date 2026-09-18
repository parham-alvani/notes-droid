package me.parham1995.notes.ui.render

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.parham1995.notes.markdown.MdInline
import me.parham1995.notes.ui.theme.Markup

/** How a tapped link should be handled, decided by the screen rather than here. */
data class InlineActions(
    val onWikiLink: (target: String, heading: String?) -> Unit = { _, _ -> },
    val onExternalLink: (url: String) -> Unit = {},
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
): Pair<AnnotatedString, List<String>> {
    val colors = MaterialTheme.colorScheme
    val formulas = mutableListOf<String>()

    val text =
        buildAnnotatedString {
            appendInlines(this, this@toAnnotated, colors, actions, brokenLinks, formulas)
        }
    return text to formulas
}

private fun appendInlines(
    builder: AnnotatedString.Builder,
    nodes: List<MdInline>,
    colors: ColorScheme,
    actions: InlineActions,
    brokenLinks: Set<String>,
    formulas: MutableList<String>,
) {
    nodes.forEach { node ->
        when (node) {
            is MdInline.Text -> builder.append(node.text)

            // @markup.raw
            is MdInline.Code ->
                builder.withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colors.surfaceVariant,
                        color = Markup.Raw,
                        fontStyle = FontStyle.Italic,
                    ),
                ) { append(node.code) }

            // @markup.italic
            is MdInline.Emphasis ->
                builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Markup.Italic)) {
                    appendInlines(builder, node.children, colors, actions, brokenLinks, formulas)
                }

            // @markup.strong
            is MdInline.Strong ->
                builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Markup.Strong)) {
                    appendInlines(builder, node.children, colors, actions, brokenLinks, formulas)
                }

            // @markup.strike
            is MdInline.Strikethrough ->
                builder.withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough, color = Markup.Strike),
                ) {
                    appendInlines(builder, node.children, colors, actions, brokenLinks, formulas)
                }

            is MdInline.Highlight ->
                builder.withStyle(
                    SpanStyle(background = colors.tertiaryContainer, color = colors.onTertiaryContainer),
                ) { appendInlines(builder, node.children, colors, actions, brokenLinks, formulas) }

            is MdInline.Link -> {
                val link =
                    LinkAnnotation.Clickable(
                        tag = node.destination,
                        styles =
                            TextLinkStyles(
                                SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
                            ),
                    ) { actions.onExternalLink(node.destination) }
                builder.withLink(link) {
                    appendInlines(builder, node.children, colors, actions, brokenLinks, formulas)
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
                        // @markup.link.label
                        SpanStyle(color = Markup.Link)
                    }
                val link =
                    LinkAnnotation.Clickable(tag = node.target, styles = TextLinkStyles(style)) {
                        actions.onWikiLink(node.target, node.heading)
                    }
                builder.withLink(link) { builder.append(node.display) }
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
