package me.parham1995.notes.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.data.BaseAnswer
import me.parham1995.notes.markdown.BaseCell
import me.parham1995.notes.markdown.BaseTable
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript

/**
 * An Obsidian base, answered: one table per view, behind tabs when there are
 * several, every view type drawn as a table. A filter that could not be read
 * is said above the table and left out, as on the Mac -- a filter dropped
 * quietly would list files the base was written to leave out.
 */
@Composable
internal fun BaseBlockView(
    block: MdBlock.BaseView,
    actions: RenderActions,
    modifier: Modifier = Modifier,
) {
    val run = actions.runBase
    if (run == null) {
        // Embedded in another note, where `this` would be a guess.
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = stringResource(R.string.unsupported_block, stringResource(R.string.base_title)),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium.inScript(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val answer by produceState<Result<BaseAnswer?>?>(null, block, run) { value = runCatching { run(block) } }
    BaseTables(
        title = block.target?.substringAfterLast('/')?.removeSuffix(".base") ?: stringResource(R.string.base_title),
        answer = answer,
        onlyView = block.view,
        onOpen = { path ->
            answer
                ?.getOrNull()
                ?.noteIds
                ?.get(path)
                ?.let { actions.inline.onNoteLink(it, null) }
        },
        modifier = modifier,
    )
}

/**
 * The tables of a base. [answer] is null while it runs, a null answer when the
 * base is not in the vault, and [onlyView] names the one view an embed asked
 * for.
 */
@Composable
internal fun BaseTables(
    title: String,
    answer: Result<BaseAnswer?>?,
    onlyView: String?,
    onOpen: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reading = LocalReading.current
    val outline = MaterialTheme.colorScheme.outlineVariant
    val result = answer?.getOrNull()?.result
    val tables =
        result?.tables.orEmpty().let { all ->
            onlyView?.let { wanted -> all.filter { it.name.equals(wanted.trim(), ignoreCase = true) }.ifEmpty { all } }
                ?: all
        }
    var selected by rememberSaveable(title, onlyView) { mutableIntStateOf(0) }
    val table = tables.getOrNull(selected.coerceAtMost(tables.lastIndex.coerceAtLeast(0)))

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, outline, RoundedCornerShape(12.dp))
            .testTag("base"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideGlyph(
                name = "table",
                size = HEADER_ICON * reading.textScale,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.inScript(),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            table?.let {
                Text(
                    text = it.count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            answer == null -> return@Column
            answer.isFailure || answer.getOrNull() == null -> {
                Note(stringResource(R.string.base_missing), error = true)
                return@Column
            }
        }
        result?.problems?.forEach { Note(it, error = true) }
        if (tables.size > 1) {
            PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                tables.forEachIndexed { index, view ->
                    Tab(
                        selected = index == selected,
                        onClick = { selected = index },
                        text = { Text(view.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        table?.let { Table(it, onOpen) }
    }
}

@Composable
private fun Note(
    text: String,
    error: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.inScript(),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The rows side by side under their headings, scrolled sideways as one so a
 * column stays a column. Fixed widths, because a lazy measure of every cell to
 * fit the widest would compose the table twice.
 */
@Composable
private fun Table(
    table: BaseTable,
    onOpen: (path: String) -> Unit,
) {
    if (table.count == 0) {
        Note(stringResource(R.string.base_empty), error = false)
        return
    }
    val scale = LocalReading.current.textScale
    val widths = table.columns.indices.map { if (it == 0) FIRST_COLUMN * scale else COLUMN * scale }
    var shown = 0
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 4.dp)) {
            table.columns.forEachIndexed { index, name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium.inScript(),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(widths[index]).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        HorizontalDivider(Modifier.width(widths.fold(0.dp) { sum, it -> sum + it } + 8.dp))
        for (group in table.groups) {
            if (shown >= MAX_ROWS) break
            group.name?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall.inScript(),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
                )
            }
            for (row in group.rows) {
                if (shown >= MAX_ROWS) break
                shown++
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    row.cells.forEachIndexed { index, cell -> Cell(cell, widths[index], onOpen) }
                }
            }
        }
    }
    val hidden = table.count - shown
    if (hidden > 0) Note(pluralStringResource(R.plurals.tasks_query_hidden, hidden, hidden), error = false)
}

@Composable
private fun Cell(
    cell: BaseCell,
    width: Dp,
    onOpen: (path: String) -> Unit,
) {
    val modifier = Modifier.width(width).padding(horizontal = 8.dp, vertical = 6.dp)
    val check = cell.check
    if (check != null) {
        Row(modifier) {
            LucideGlyph(
                name = if (check) "square-check-big" else "square",
                size = BOX_ICON * LocalReading.current.textScale,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = check.toString(),
            )
        }
        return
    }
    // One link alone takes the whole cell's tap, which is the common case --
    // the name column -- and the easiest target to hit.
    val link = cell.parts.singleOrNull()?.path
    Text(
        text = cell.text,
        style = MaterialTheme.typography.bodyMedium.inScript(),
        color = if (link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        textAlign = if (cell.numeric) TextAlign.End else TextAlign.Start,
        maxLines = MAX_CELL_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = if (link != null) modifier.clickable(role = Role.Button) { onOpen(link) } else modifier,
    )
}

/** Every row is composed at once inside the note's lazy list, so a whole vault would stall a frame. */
private const val MAX_ROWS = 150
private const val MAX_CELL_LINES = 3
private val HEADER_ICON = 18.dp
private val BOX_ICON = 16.dp
private val FIRST_COLUMN = 180.dp
private val COLUMN = 140.dp
