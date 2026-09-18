package me.parham1995.notes.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.parham1995.notes.markdown.CalloutKind
import me.parham1995.notes.markdown.MdAlign
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.MdDirection
import me.parham1995.notes.markdown.MdInline
import me.parham1995.notes.markdown.MdListItem
import me.parham1995.notes.markdown.TaskState
import me.parham1995.notes.ui.theme.Markup

/** Everything a rendered block might need to hand back to the screen. */
data class RenderActions(
    val inline: InlineActions = InlineActions(),
    val onImage: (path: String) -> Unit = {},
    val onAttachment: (path: String) -> Unit = {},
    val onCopyCode: (String) -> Unit = {},
)

@Composable
fun MdBlockView(
    block: MdBlock,
    actions: RenderActions,
    brokenLinks: Set<String>,
    modifier: Modifier = Modifier,
) {
    // Direction is per block, so a Persian paragraph and the Latin code block
    // under it each read correctly in the same note.
    val direction = if (block.direction == MdDirection.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        when (block) {
            is MdBlock.Heading -> HeadingView(block, actions, brokenLinks, modifier)
            is MdBlock.Paragraph ->
                RichText(
                    inlines = block.inlines,
                    modifier = modifier.fillMaxWidth(),
                    // Carries the inherited slant so a quoted paragraph stays
                    // italic, while the colour arrives via LocalContentColor.
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontStyle = LocalTextStyle.current.fontStyle,
                        ),
                    actions = actions.inline,
                    brokenLinks = brokenLinks,
                )

            is MdBlock.CodeBlock -> CodeBlockView(block, actions, modifier)
            is MdBlock.Mermaid -> MermaidBlockView(block.code, modifier)
            is MdBlock.MathBlock -> MathBlockView(block.latex, modifier)
            is MdBlock.Callout -> CalloutView(block, actions, brokenLinks, modifier)
            is MdBlock.Quote -> QuoteView(block, actions, brokenLinks, modifier)
            is MdBlock.ListBlock -> ListBlockView(block, actions, brokenLinks, modifier)
            is MdBlock.Table -> TableView(block, actions, brokenLinks, modifier)
            is MdBlock.Image -> VaultImage(block.path, block.alt, modifier, onClick = { actions.onImage(block.path) })
            is MdBlock.Attachment -> AttachmentView(block, actions, modifier)
            is MdBlock.ThematicBreak -> HorizontalDivider(modifier.padding(vertical = 8.dp))
            is MdBlock.Unsupported -> UnsupportedView(block.label, modifier)
            // Front matter is metadata; only `direction` and `cssclasses` ever
            // affected rendering, and both are handled by detection instead.
            is MdBlock.FrontMatter -> Unit
        }
    }
}

@Composable
private fun HeadingView(
    block: MdBlock.Heading,
    actions: RenderActions,
    brokenLinks: Set<String>,
    modifier: Modifier,
) {
    val style =
        when (block.level) {
            1 -> MaterialTheme.typography.headlineMedium
            2 -> MaterialTheme.typography.headlineSmall
            3 -> MaterialTheme.typography.titleLarge
            4 -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleSmall
        }
    Column(modifier.fillMaxWidth()) {
        // More space above than below, so a heading belongs to what follows it
        // rather than floating between two sections.
        Spacer(Modifier.height(if (block.level <= 2) 18.dp else 12.dp))
        RichText(
            inlines = block.inlines,
            modifier = Modifier.fillMaxWidth(),
            // naz gives @markup.heading.1 through .4 their own colours, and
            // this follows them rather than picking a scale.
            style = style.copy(color = Markup.heading(block.level)),
            actions = actions.inline,
            brokenLinks = brokenLinks,
        )
        if (block.level <= 2) {
            HorizontalDivider(
                Modifier.padding(top = 6.dp),
                color = Markup.heading(block.level).copy(alpha = RULE_ALPHA),
            )
        }
    }
}

@Composable
private fun CodeBlockView(
    block: MdBlock.CodeBlock,
    actions: RenderActions,
    modifier: Modifier,
) {
    var highlighted by remember(block.id) { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(block.id) {
        highlighted = CodeHighlighter.highlight(block.code, block.language)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            block.language?.let { language ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        // Dimmer when the label is only a label: nothing is
                        // being highlighted for it.
                        color =
                            if (CodeHighlighter.isKnown(language)) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                    )
                    Text(
                        text = "copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { actions.onCopyCode(block.code) },
                    )
                }
            }
            // Code is left-to-right whatever the surrounding prose does, and
            // scrolls on its own rather than wrapping mid-statement.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = highlighted ?: AnnotatedString(block.code),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = CODE_LINE_HEIGHT),
                )
            }
        }
    }
}

@Composable
private fun CalloutView(
    block: MdBlock.Callout,
    actions: RenderActions,
    brokenLinks: Set<String>,
    modifier: Modifier,
) {
    var expanded by remember(block.id) { mutableStateOf(!block.collapsed) }
    val accent = block.kind.accent()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = CONTAINER_ALPHA)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = block.collapsed) { expanded = !expanded },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(block.kind.glyph(), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = block.title.plainText().ifBlank { block.kind.label() },
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (expanded) {
                block.children.forEach { child ->
                    MdBlockView(child, actions, brokenLinks)
                }
            }
        }
    }
}

@Composable
private fun QuoteView(
    block: MdBlock.Quote,
    actions: RenderActions,
    brokenLinks: Set<String>,
    modifier: Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .width(3.dp)
                .background(Markup.Quote, RoundedCornerShape(2.dp)),
        )
        // @markup.quote paints the quoted text itself, not just the rule.
        CompositionLocalProvider(
            LocalContentColor provides Markup.Quote,
            LocalTextStyle provides LocalTextStyle.current.copy(fontStyle = FontStyle.Italic),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.children.forEach { MdBlockView(it, actions, brokenLinks) }
            }
        }
    }
}

@Composable
private fun ListBlockView(
    block: MdBlock.ListBlock,
    actions: RenderActions,
    brokenLinks: Set<String>,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.items.forEachIndexed { index, item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = item.marker(block.ordered, block.start + index),
                    style = MaterialTheme.typography.bodyLarge,
                    color = item.markerColor(),
                    modifier = Modifier.widthIn(min = MARKER_WIDTH),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.blocks.forEach { child ->
                        // A nested list indents; anything else sits flush with
                        // the item's own text.
                        val indent = if (child is MdBlock.ListBlock) NESTED_LIST_INDENT else 0.dp
                        MdBlockView(child, actions, brokenLinks, Modifier.padding(start = indent))
                    }
                    if (item.taskMeta.isNotEmpty()) TaskChips(item)
                }
            }
        }
    }
}

@Composable
private fun TaskChips(item: MdListItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item.taskMeta.forEach { meta ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = "${meta.emoji} ${meta.value}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TableView(
    block: MdBlock.Table,
    actions: RenderActions,
    brokenLinks: Set<String>,
    modifier: Modifier,
) {
    // Columns sized from their own content rather than all alike. A
    // two-column table of short values looked absurd at a fixed width, and a
    // fourteen-column one needs every column it can get.
    val widths =
        remember(block.id) {
            val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
            List(columns) { column ->
                val longest =
                    (listOf(block.header.getOrNull(column)) + block.rows.map { it.getOrNull(column) })
                        .filterNotNull()
                        .maxOfOrNull { cell -> cell.textLength() } ?: 0
                (longest * APPROX_CHAR_WIDTH).dp.coerceIn(MIN_CELL_WIDTH, MAX_CELL_WIDTH)
            }
        }

    // The scroll is on this block alone, never the page: tables run to a dozen
    // columns and would otherwise make the whole note scroll sideways.
    Column(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
    ) {
        if (block.header.isNotEmpty()) {
            TableRowView(block.header, block.alignments, widths, actions, brokenLinks, header = true)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        block.rows.forEachIndexed { index, row ->
            TableRowView(row, block.alignments, widths, actions, brokenLinks, header = false)
            if (index != block.rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun TableRowView(
    cells: List<List<MdInline>>,
    alignments: List<MdAlign>,
    widths: List<Dp>,
    actions: RenderActions,
    brokenLinks: Set<String>,
    header: Boolean,
) {
    Row(Modifier.background(if (header) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)) {
        cells.forEachIndexed { index, cell ->
            RichText(
                inlines = cell,
                modifier = Modifier.width(widths.getOrElse(index) { MIN_CELL_WIDTH }).padding(8.dp),
                style =
                    if (header) {
                        MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                actions = actions.inline,
                brokenLinks = brokenLinks,
                textAlign =
                    when (alignments.getOrNull(index)) {
                        MdAlign.CENTER -> TextAlign.Center
                        MdAlign.END -> TextAlign.End
                        else -> TextAlign.Start
                    },
            )
        }
    }
}

private fun List<MdInline>.textLength(): Int =
    sumOf { node ->
        when (node) {
            is MdInline.Text -> node.text.length
            is MdInline.Code -> node.code.length
            is MdInline.Emphasis -> node.children.textLength()
            is MdInline.Strong -> node.children.textLength()
            is MdInline.Strikethrough -> node.children.textLength()
            is MdInline.Highlight -> node.children.textLength()
            is MdInline.Link -> node.children.textLength()
            is MdInline.WikiLink -> node.display.length
            is MdInline.InlineMath -> node.latex.length
            else -> 1
        }
    }

@Composable
private fun AttachmentView(
    block: MdBlock.Attachment,
    actions: RenderActions,
    modifier: Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable { actions.onAttachment(block.path) },
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▶", style = MaterialTheme.typography.titleMedium)
            Column {
                Text(block.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Open with another app",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UnsupportedView(
    label: String,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            // Saying so plainly beats showing the reader the query source.
            text = "$label - not supported here",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun List<MdInline>.plainText(): String = joinToString("") { if (it is MdInline.Text) it.text else "" }

private fun MdListItem.marker(
    ordered: Boolean,
    number: Int,
): String =
    when (task) {
        TaskState.UNCHECKED -> "☐"
        TaskState.CHECKED -> "☑"
        TaskState.CANCELLED -> "☒"
        // Obsidian's in-progress state has no standard glyph; a half-filled
        // box reads closest.
        TaskState.IN_PROGRESS -> "◧"
        TaskState.NONE -> if (ordered) "$number." else "•"
    }

@Composable
private fun MdListItem.markerColor(): Color =
    when (task) {
        TaskState.CHECKED, TaskState.CANCELLED -> MaterialTheme.colorScheme.outline
        TaskState.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        else -> Markup.ListMarker
    }

@Composable
private fun CalloutKind.accent(): Color =
    when (this) {
        CalloutKind.WARNING, CalloutKind.FAILURE -> MaterialTheme.colorScheme.tertiary
        CalloutKind.DANGER, CalloutKind.BUG -> MaterialTheme.colorScheme.error
        CalloutKind.SUCCESS -> MaterialTheme.colorScheme.primary
        CalloutKind.TIP, CalloutKind.INFO -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }

private fun CalloutKind.glyph(): String =
    when (this) {
        CalloutKind.WARNING, CalloutKind.FAILURE -> "\u26A0"
        CalloutKind.DANGER, CalloutKind.BUG -> "\u26D4"
        CalloutKind.SUCCESS, CalloutKind.TODO -> "\u2713"
        CalloutKind.QUOTE -> "\u201C"
        CalloutKind.TIP -> "\u2605"
        else -> "ℹ"
    }

private fun CalloutKind.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private const val CONTAINER_ALPHA = 0.10f
private const val RULE_ALPHA = 0.35f
private val CODE_LINE_HEIGHT = 18.sp
private const val APPROX_CHAR_WIDTH = 8
private val MIN_CELL_WIDTH = 72.dp
private val MAX_CELL_WIDTH = 240.dp
private val NESTED_LIST_INDENT = 12.dp
private val MARKER_WIDTH = 18.dp
