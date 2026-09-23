package me.parham1995.notes.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.parham1995.notes.R
import me.parham1995.notes.markdown.CalloutKind
import me.parham1995.notes.markdown.MdAlign
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.MdDirection
import me.parham1995.notes.markdown.MdInline
import me.parham1995.notes.markdown.MdListItem
import me.parham1995.notes.markdown.TaskMeta
import me.parham1995.notes.markdown.TaskState
import me.parham1995.notes.markdown.isFinishedAndEmpty
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.theme.Markup
import me.parham1995.notes.ui.theme.Naz
import me.parham1995.notes.ui.theme.Vazirmatn

/** Everything a rendered block might need to hand back to the screen. */
data class RenderActions(
    /**
     * Which vault the note being rendered belongs to. Its embeds are resolved
     * against that vault alone -- an image in one is not an image in another,
     * even at the same path.
     */
    val vaultId: Long = 0,
    val inline: InlineActions = InlineActions(),
    val onImage: (path: String, alt: String?) -> Unit = { _, _ -> },
    val onAttachment: (path: String) -> Unit = {},
    val onCopyCode: (String) -> Unit = {},
    /**
     * Tick the open task written on this source line.
     *
     * Null when this vault cannot be written to, which is what keeps the
     * checkbox inert rather than offering something that fails at the push.
     */
    val onCompleteTask: ((line: Int) -> Unit)? = null,
    /**
     * Another note's blocks for an `![[embed]]`, the section its heading names
     * when it names one, or null when there is no such note or heading.
     *
     * Null as a whole means embeds are drawn as links only.
     */
    val transclude: (suspend (target: String, heading: String?) -> Transcluded?)? = null,
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
    val rtl = block.direction == MdDirection.RTL
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        // Persian prose is set in Vazirmatn; the Latin paragraph after it, and
        // the code block after that, are not. Per block, like the direction.
        LocalTextStyle provides
            LocalTextStyle.current.copy(
                fontFamily = if (rtl && LocalReading.current.persianFont) Vazirmatn else null,
            ),
    ) {
        when (block) {
            is MdBlock.Heading -> HeadingView(block, actions, brokenLinks, modifier)
            is MdBlock.Paragraph ->
                RichText(
                    inlines = block.inlines,
                    modifier = modifier.fillMaxWidth(),
                    // Carries the inherited slant so a quoted paragraph stays
                    // italic, while the colour arrives via LocalContentColor.
                    style =
                        MaterialTheme.typography.bodyLarge
                            .copy(fontStyle = LocalTextStyle.current.fontStyle)
                            .inScript(),
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
            is MdBlock.Image ->
                VaultImage(
                    vaultId = actions.vaultId,
                    path = block.path,
                    alt = block.alt,
                    width = block.width,
                    height = block.height,
                    modifier = modifier,
                    onClick = { actions.onImage(block.path, block.alt) },
                )
            is MdBlock.Attachment -> AttachmentView(block, actions, modifier)
            is MdBlock.NoteEmbed -> NoteEmbedView(block, actions, modifier)
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
            style = style.copy(color = Markup.heading(block.level)).inScript(),
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

    // A bar down the side rather than a tinted box alone. Obsidian's own
    // callouts read as a margin note, and the bar is what does that: the tint
    // says which kind, the bar says where it starts and stops -- which matters
    // in this vault, where a callout regularly contains a fenced code block
    // with a background of its own.
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = CONTAINER_ALPHA))
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(ACCENT_BAR).fillMaxHeight().background(accent))
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                // `+` and `-` both fold; `+` just starts open. Only `-` used to
                // be tappable, so a callout written to start open could never
                // be shut.
                modifier = Modifier.fillMaxWidth().clickable(enabled = block.foldable) { expanded = !expanded },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideGlyph(
                    name = block.kind.icon(),
                    size = CALLOUT_ICON,
                    tint = accent,
                    contentDescription = block.label(),
                )
                // A title is inline content like any other: a link in it is a
                // link, and code is code. Flattened to plain text, both were
                // just words.
                if (block.title.isEmpty()) {
                    Text(
                        text = block.label(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall.inScript(),
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    RichText(
                        inlines = block.title,
                        modifier = Modifier.weight(1f),
                        style =
                            MaterialTheme.typography.titleSmall
                                .copy(color = accent, fontWeight = FontWeight.SemiBold)
                                .inScript(),
                        actions = actions.inline,
                        brokenLinks = brokenLinks,
                    )
                }
                // Says it folds, and which way it is: a header that collapses
                // the body when tapped looked exactly like one that does not.
                if (block.foldable) {
                    LucideGlyph(
                        name = if (expanded) "chevron-down" else "chevron-right",
                        size = CALLOUT_ICON,
                        tint = accent,
                    )
                }
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
    // Numbered by the item's own position, not by where it lands after
    // filtering: an ordered list that renumbered itself when a done item was
    // hidden would disagree with the file it came from.
    val visible =
        if (!LocalReading.current.hideCompletedTasks) {
            block.items.withIndex().toList()
        } else {
            block.items.withIndex().filterNot { (_, item) -> item.isFinishedAndEmpty() }
        }
    // Nothing left to draw. The block stays in the document -- the outline and
    // find-in-note scroll to block positions, and dropping one would move every
    // target after it -- it simply takes no room.
    if (visible.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        visible.forEach { (index, item) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ItemMarker(item, block.ordered, block.start + index, actions.onCompleteTask)
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

/**
 * The bullet, the number, or -- for a task -- its state.
 *
 * A checkbox is the one list marker that carries meaning rather than just
 * separating items, and `[ ] [x] [-] [/]` are four states this vault actually
 * uses. As text glyphs they were four boxes that differed by a few pixels of
 * interior; as icons they differ by shape and colour.
 */
@Composable
private fun ItemMarker(
    item: MdListItem,
    ordered: Boolean,
    number: Int,
    onComplete: ((line: Int) -> Unit)? = null,
) {
    val icon =
        when (item.task) {
            TaskState.UNCHECKED -> "square"
            TaskState.CHECKED -> "square-check-big"
            TaskState.CANCELLED -> "square-x"
            TaskState.IN_PROGRESS -> "square-dot"
            TaskState.NONE -> null
        }
    if (icon == null) {
        Text(
            text = if (ordered) "$number." else "\u2022",
            style = MaterialTheme.typography.bodyLarge,
            color = Markup.ListMarker,
            modifier = Modifier.widthIn(min = MARKER_WIDTH),
        )
        return
    }
    // Only an open task, only where the line is known, and only where the vault
    // can be written to. A box that ticks in some notes and not others is worse
    // than one that never does, so the three conditions are checked together.
    val open = item.task == TaskState.UNCHECKED || item.task == TaskState.IN_PROGRESS
    val complete = onComplete?.takeIf { open && item.line >= 0 }
    LucideGlyph(
        name = icon,
        // Nudged down to sit on the first line of the item's text rather than
        // above it; an icon has no baseline of its own.
        modifier =
            Modifier
                .widthIn(min = MARKER_WIDTH)
                .padding(top = MARKER_NUDGE)
                .then(complete?.let { Modifier.clickable { it(item.line) } } ?: Modifier),
        size = MARKER_ICON,
        tint = item.markerColor(),
        contentDescription =
            item.task.name
                .lowercase()
                .replace('_', ' '),
    )
}

/**
 * The Obsidian Tasks plugin's trailing emoji dates, as chips.
 *
 * All of them were one grey, so a task that was done and one that is overdue
 * looked the same until you read the date. The emoji went with the colour --
 * at chip size it was a smudge, and the colour already says which kind it is.
 */
@Composable
private fun TaskChips(item: MdListItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item.taskMeta.forEach { meta ->
            val (icon, tint, label) = meta.chip()
            Surface(
                color = tint.copy(alpha = CHIP_ALPHA),
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LucideGlyph(name = icon, size = CHIP_ICON, tint = tint, contentDescription = label)
                    Text(
                        text = meta.value,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
            }
        }
    }
}

/** Icon, colour and a spoken label for one of the Tasks plugin's date fields. */
private fun TaskMeta.chip(): Triple<String, Color, String> =
    when (emoji) {
        "\u2705" -> Triple("check", Naz.SpringGreen, "done")
        "\u274C" -> Triple("x", Naz.Red, "cancelled")
        "\u23F3" -> Triple("hourglass", Naz.Blue, "scheduled")
        "\uD83D\uDCC5" -> Triple("calendar", Naz.Orange, "due")
        "\uD83D\uDD01" -> Triple("repeat", Naz.Purple, "repeats")
        "\uD83D\uDEEB" -> Triple("plane-takeoff", Naz.Aqua, "starts")
        // The one that is on almost every task in this vault, and the least
        // interesting of them, so it stays quiet.
        "\u2795" -> Triple("plus", Naz.Grey, "added")
        else -> Triple("info", Naz.Grey, "")
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
            // Striping rather than a rule between every row: these tables run
            // to fourteen columns, and at that width the eye loses the row long
            // before it runs out of columns.
            TableRowView(row, block.alignments, widths, actions, brokenLinks, header = false, striped = index % 2 == 1)
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
    striped: Boolean = false,
) {
    val background =
        when {
            header -> MaterialTheme.colorScheme.surfaceVariant
            striped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = STRIPE_ALPHA)
            else -> Color.Transparent
        }
    Row(Modifier.background(background)) {
        cells.forEachIndexed { index, cell ->
            RichText(
                inlines = cell,
                modifier = Modifier.width(widths.getOrElse(index) { MIN_CELL_WIDTH }).padding(8.dp),
                style =
                    if (header) {
                        MaterialTheme.typography.labelLarge
                            .copy(fontWeight = FontWeight.SemiBold)
                            .inScript()
                    } else {
                        MaterialTheme.typography.bodyMedium.inScript()
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
            LucideGlyph(
                name = Attachments.iconOf(block.path),
                size = ATTACHMENT_ICON,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(block.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.attachment_open_with),
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

@Composable
private fun MdListItem.markerColor(): Color =
    when (task) {
        TaskState.CHECKED, TaskState.CANCELLED -> MaterialTheme.colorScheme.outline
        TaskState.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        else -> Markup.ListMarker
    }

/**
 * A colour per kind, from naz rather than from the Material roles.
 *
 * The Material scheme only carries three accents, so a warning and a tip came
 * out the same colour and everything else fell back to primary. The top five
 * kinds are 93% of the 1,302 callouts in this vault, so telling those apart at
 * a glance is most of what a callout is for.
 */
private fun CalloutKind.accent(): Color =
    when (this) {
        CalloutKind.WARNING -> Naz.Orange
        CalloutKind.DANGER, CalloutKind.BUG, CalloutKind.FAILURE -> Naz.Red
        CalloutKind.SUCCESS -> Naz.SpringGreen
        CalloutKind.TIP -> Naz.Chartreuse
        CalloutKind.TODO -> Naz.VividYellow
        CalloutKind.QUESTION -> Naz.Yellow
        CalloutKind.ABSTRACT -> Naz.LimeGreen
        CalloutKind.EXAMPLE -> Naz.Purple
        CalloutKind.QUOTE -> Naz.WarmGrey
        CalloutKind.FIGURE -> Naz.Pink
        CalloutKind.READ -> Naz.Aqua
        CalloutKind.INFO, CalloutKind.NOTE -> Naz.Blue
    }

private fun CalloutKind.icon(): String =
    when (this) {
        CalloutKind.WARNING -> "triangle-alert"
        CalloutKind.DANGER -> "octagon-alert"
        CalloutKind.FAILURE -> "circle-x"
        CalloutKind.BUG -> "bug"
        CalloutKind.SUCCESS -> "circle-check"
        CalloutKind.TIP -> "lightbulb"
        CalloutKind.TODO -> "list-todo"
        CalloutKind.QUESTION -> "circle-help"
        CalloutKind.ABSTRACT -> "clipboard-list"
        CalloutKind.EXAMPLE -> "flask-conical"
        CalloutKind.QUOTE -> "quote"
        CalloutKind.FIGURE -> "image"
        CalloutKind.READ -> "book-open"
        CalloutKind.INFO, CalloutKind.NOTE -> "info"
    }

/**
 * What an untitled callout is called: its type as written, capitalised, which
 * is what Obsidian shows. `[!recipe]` falls back to a note's colours and icon,
 * but it is still called "Recipe" -- labelling it "Note" lost the one thing
 * the author said about it.
 */
private fun MdBlock.Callout.label(): String = type.replaceFirstChar { it.uppercase() }

private const val CONTAINER_ALPHA = 0.10f
private const val CHIP_ALPHA = 0.16f
private const val STRIPE_ALPHA = 0.35f
private val ACCENT_BAR = 3.dp
private val CALLOUT_ICON = 16.dp
private val CHIP_ICON = 11.dp
private val ATTACHMENT_ICON = 22.dp
private val MARKER_ICON = 16.dp
private val MARKER_NUDGE = 3.dp
private const val RULE_ALPHA = 0.35f
private val CODE_LINE_HEIGHT = 18.sp
private const val APPROX_CHAR_WIDTH = 8
private val MIN_CELL_WIDTH = 72.dp
private val MAX_CELL_WIDTH = 240.dp
private val NESTED_LIST_INDENT = 12.dp
private val MARKER_WIDTH = 18.dp
