package me.parham1995.notes.feature.board

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.data.database.QueryTaskRow
import me.parham1995.notes.markdown.StatusType
import me.parham1995.notes.markdown.TaskBoard
import me.parham1995.notes.markdown.TaskStatuses
import me.parham1995.notes.ui.AutoDirection
import me.parham1995.notes.ui.TaskReasonDialog
import me.parham1995.notes.ui.TaskReasonLine
import me.parham1995.notes.ui.TaskStatusBadge
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.text
import me.parham1995.notes.ui.theme.Accents
import me.parham1995.notes.ui.theme.wash
import java.time.LocalDate
import kotlin.math.absoluteValue

/**
 * The active vault's tasks on a board, as the Mac app draws them: by status,
 * by day or by file, a column each, side by side.
 *
 * A phone cannot drag a card across a screen it can only show one column of,
 * so a held card offers "Move to …" for every column it can go to instead.
 * What a move writes is the board's rule ([TaskBoard]) through the one write
 * path there is; a vault the app cannot push to shows the board and offers
 * neither the menu nor the checkbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    onBack: () -> Unit,
    onOpenNote: (noteId: Long, heading: String?) -> Unit,
    viewModel: BoardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Said once and cleared, as on the task list.
    val message = state.message?.text()
    val undo = state.undo
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(message, undo) {
        message?.let {
            val answer =
                snackbar.showSnackbar(
                    message = it,
                    actionLabel = undo?.let { undoLabel },
                    duration = SnackbarDuration.Short,
                )
            if (answer == SnackbarResult.ActionPerformed && undo != null) viewModel.undo(undo)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.board_title))
                        if (state.open > 0) {
                            Text(
                                text = pluralStringResource(R.plurals.tasks_open_count, state.open, state.open),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (state.sources.size > 1) NoteFilter(state, viewModel::filterBy)
                },
            )
        },
    ) { padding ->
        // A column, not two things side by side in the scaffold's box.
        Column(Modifier.fillMaxSize().padding(padding)) {
            BoardControls(
                grouping = state.grouping,
                allClosed = state.allClosed,
                onGroup = viewModel::groupBy,
                onAllClosed = viewModel::showAllClosed,
            )
            if (!state.loading) {
                BoardColumns(
                    columns = state.columns,
                    canWrite = state.canWrite,
                    today = LocalDate.now(),
                    onMove = { row, drop -> viewModel.move(row, drop) },
                    onToggle = { viewModel.toggle(it) },
                    onOpen = { row -> onOpenNote(row.noteId, row.section.takeIf { it.isNotBlank() }) },
                    modifier = Modifier.weight(1f),
                    statuses = state.statuses,
                    onSetStatus = { row, status, reason -> viewModel.setStatus(row, status, reason) },
                    onSetReason = { row, reason -> viewModel.setReason(row, reason) },
                )
            }
        }
    }
}

/** The grouping, and for the status board whether every closed task shows. */
@Composable
internal fun BoardControls(
    grouping: TaskBoard.Grouping,
    allClosed: Boolean,
    onGroup: (TaskBoard.Grouping) -> Unit,
    onAllClosed: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val groupings = TaskBoard.Grouping.entries
        SingleChoiceSegmentedButtonRow {
            groupings.forEachIndexed { index, by ->
                SegmentedButton(
                    selected = by == grouping,
                    onClick = { onGroup(by) },
                    shape = SegmentedButtonDefaults.itemShape(index, groupings.size),
                    icon = {},
                    label = { Text(stringResource(by.label())) },
                )
            }
        }
        if (grouping == TaskBoard.Grouping.STATUS) {
            FilterChip(
                selected = allClosed,
                onClick = { onAllClosed(!allClosed) },
                label = { Text(stringResource(R.string.board_all_closed)) },
                leadingIcon = { LucideGlyph("archive", size = 16.dp) },
            )
        }
    }
}

/**
 * The columns themselves, and the menu a held card opens.
 *
 * Apart from the screen so it can be driven without a view model: what is
 * worth testing is that holding a card and choosing a column hands back that
 * card and that column's move.
 *
 * With [onSetStatus], the menu also lists every status of the vault by name
 * and symbol, and anything that cancels -- a cancelled-typed status, the
 * Cancelled column -- asks for the reason first. With [onSetReason] it offers
 * to write, change or clear the reason, whatever the status.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BoardColumns(
    columns: List<TaskBoard.Column<QueryTaskRow>>,
    canWrite: Boolean,
    today: LocalDate,
    onMove: (QueryTaskRow, TaskBoard.Drop) -> Unit,
    onToggle: (QueryTaskRow) -> Unit,
    onOpen: (QueryTaskRow) -> Unit,
    modifier: Modifier = Modifier,
    statuses: TaskStatuses = TaskStatuses.DEFAULT,
    onSetStatus: ((QueryTaskRow, Char, String?) -> Unit)? = null,
    onSetReason: ((QueryTaskRow, String) -> Unit)? = null,
) {
    val targets = columns.filter { it.drop != null }
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // One column at a time, as a phone shows them.
        flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Start),
    ) {
        items(columns, key = { it.id }) { column ->
            val color = columnColor(column)
            val title = column.label()
            Column(
                Modifier
                    .fillParentMaxWidth(COLUMN_SHARE)
                    .widthIn(max = COLUMN_MAX)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(color.wash(MaterialTheme.colorScheme.background, COLUMN_WASH))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColumnHeader(title, column.tasks.size, color)
                if (column.tasks.isEmpty()) {
                    Text(
                        stringResource(R.string.board_column_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(column.tasks, key = { it.id }) { row ->
                        // Every column it could go to but the one it is in.
                        val moves = if (canWrite) targets.filter { it.id != column.id } else emptyList()
                        BoardCard(
                            row = row,
                            color = color,
                            today = today,
                            moves = moves.map { it.label() to it.drop!! },
                            onMove = { drop -> onMove(row, drop) },
                            onToggle = if (canWrite) ({ onToggle(row) }) else null,
                            onOpen = { onOpen(row) },
                            statuses = statuses,
                            onSetStatus = onSetStatus?.takeIf { canWrite }?.let { set -> { s, r -> set(row, s, r) } },
                            onSetReason = onSetReason?.takeIf { canWrite }?.let { set -> { r -> set(row, r) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnHeader(
    title: String,
    count: Int,
    color: Color,
) {
    Surface(
        color = color.wash(MaterialTheme.colorScheme.surfaceContainer, HEADER_WASH),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(color.copy(alpha = COUNT_ALPHA))
                        .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoardCard(
    row: QueryTaskRow,
    color: Color,
    today: LocalDate,
    moves: List<Pair<String, TaskBoard.Drop>>,
    onMove: (TaskBoard.Drop) -> Unit,
    onToggle: (() -> Unit)?,
    onOpen: () -> Unit,
    statuses: TaskStatuses = TaskStatuses.DEFAULT,
    onSetStatus: ((Char, String?) -> Unit)? = null,
    onSetReason: ((String) -> Unit)? = null,
) {
    val facts = row.facts(statuses)
    var menu by remember { mutableStateOf(false) }
    // The status a cancel is waiting on the reason for, or the reason alone
    // being edited (a null status); nothing asked when the dialog is shut.
    var asking by remember { mutableStateOf<Pair<Char?, Boolean>?>(null) }
    val pickable = onSetStatus?.let { statuses.all.filter { it.symbol != facts.status.symbol } }.orEmpty()
    val holdable = moves.isNotEmpty() || pickable.isNotEmpty() || onSetReason != null
    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(11.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onOpen,
                        // Held rather than dragged: the phone's way to move a card.
                        onLongClick = if (!holdable) null else ({ menu = true }),
                        onLongClickLabel = if (!holdable) null else stringResource(R.string.board_move_action),
                    ),
        ) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(color))
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusBox(facts.status.type, color, onToggle)
                        AutoDirection(row.text) {
                            Text(
                                text = row.text,
                                style = MaterialTheme.typography.bodyMedium.inScript(),
                                color =
                                    if (facts.isOpen) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                textDecoration = if (facts.isOpen) null else TextDecoration.LineThrough,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    // A status beyond the four, by its name; the reason, quieter.
                    if (!facts.status.isDefault) TaskStatusBadge(facts.status.name)
                    facts.reason?.let { TaskReasonLine(it) }
                    CardFooter(row, facts, today)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            moves.forEach { (title, drop) ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.board_move_to, title)) },
                    onClick = {
                        menu = false
                        val cancels =
                            drop is TaskBoard.Drop.Status && statuses.of(drop.status).type == StatusType.CANCELLED
                        if (cancels && onSetStatus != null) {
                            asking = drop.status to true
                        } else {
                            onMove(drop)
                        }
                    },
                )
            }
            pickable.forEach { status ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.board_set_status, "${status.name} [${status.symbol}]")) },
                    onClick = {
                        menu = false
                        if (status.type == StatusType.CANCELLED) {
                            asking = status.symbol to true
                        } else {
                            onSetStatus?.invoke(status.symbol, null)
                        }
                    },
                )
            }
            if (onSetReason != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.board_edit_reason)) },
                    leadingIcon = { LucideGlyph("message-circle", size = 16.dp) },
                    onClick = {
                        menu = false
                        asking = null to false
                    },
                )
            }
        }
    }
    asking?.let { (status, cancelling) ->
        TaskReasonDialog(
            current = facts.reason,
            cancelling = cancelling,
            onDismiss = { asking = null },
            onConfirm = { reason ->
                asking = null
                if (status != null) onSetStatus?.invoke(status, reason) else onSetReason?.invoke(reason)
            },
        )
    }
}

/** The checkbox, ticking as it does everywhere else, and only where a write can land. */
@Composable
private fun StatusBox(
    type: StatusType,
    color: Color,
    onToggle: (() -> Unit)?,
) {
    val open = !type.isClosed
    LucideGlyph(
        name =
            when (type) {
                StatusType.IN_PROGRESS -> "square-dot"
                StatusType.DONE -> "square-check"
                StatusType.CANCELLED, StatusType.NON_TASK -> "square-x"
                StatusType.TODO -> "square"
            },
        tint = color,
        modifier =
            Modifier
                .padding(top = 2.dp)
                .then(
                    onToggle?.let { toggle ->
                        Modifier
                            .semantics { toggleableState = if (open) ToggleableState.Off else ToggleableState.On }
                            .clickable(
                                onClickLabel =
                                    stringResource(
                                        if (open) R.string.task_complete_action else R.string.board_reopen_action,
                                    ),
                                role = Role.Checkbox,
                                onClick = toggle,
                            )
                    } ?: Modifier,
                ),
        contentDescription =
            stringResource(
                if (type == StatusType.IN_PROGRESS) {
                    R.string.task_state_in_progress
                } else {
                    R.string.task_state_open
                },
            ).takeIf { open },
    )
}

/** Note › section, then the repeat, the priority and the day. */
@Composable
private fun CardFooter(
    row: QueryTaskRow,
    facts: TaskBoard.Facts,
    today: LocalDate,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = row.noteTitle,
                style = MaterialTheme.typography.labelSmall,
                color = Accents.aqua,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.section.isNotBlank()) {
                Text(
                    text = "› ${row.section}",
                    style = MaterialTheme.typography.labelSmall.inScript(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (row.recurring != null) LucideGlyph("repeat", size = 12.dp)
        priorityMark(facts.priority)?.let { (glyph, tint) -> LucideGlyph(glyph, size = 14.dp, tint = tint) }
        facts.day?.let { day -> DayChip(day, due = row.due != null, open = facts.isOpen, today = today) }
    }
}

@Composable
private fun DayChip(
    day: String,
    due: Boolean,
    open: Boolean,
    today: LocalDate,
) {
    val date = runCatching { LocalDate.parse(day) }.getOrNull()
    val color =
        when {
            !open || date == null -> Accents.grey
            date.isBefore(today) -> Accents.red
            date == today -> MaterialTheme.colorScheme.primary
            else -> Accents.blue
        }
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = CHIP_ALPHA))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        LucideGlyph(if (due) "calendar" else "hourglass", size = 11.dp, tint = color)
        Text(day, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** A Lucide glyph and its colour for a priority mark, or null for none. */
@Composable
private fun priorityMark(priority: String?): Pair<String, Color>? =
    when (priority) {
        "🔺" -> "chevrons-up" to Accents.red
        "⏫" -> "chevrons-up" to Accents.orange
        "🔼" -> "chevron-up" to Accents.vividYellow
        "🔽" -> "chevron-down" to Accents.blue
        "⏬" -> "chevrons-down" to Accents.grey
        else -> null
    }

/** The colour of a column, as the Mac app picks it; a file's from its name. */
@Composable
private fun columnColor(column: TaskBoard.Column<*>): Color =
    when (column.id) {
        "todo", "today" -> MaterialTheme.colorScheme.primary
        "doing", "week" -> Accents.blue
        "done" -> Accents.springGreen
        "overdue" -> Accents.red
        "tomorrow" -> Accents.vividYellow
        "later" -> Accents.purple
        "cancelled", "none" -> Accents.grey
        else ->
            Accents.let { listOf(it.aqua, it.purple, it.pink, it.chartreuse, it.orange, it.blue) }.let { palette ->
                palette[column.title.hashCode().absoluteValue % palette.size]
            }
    }

/** A fixed column's name in the reader's language; a file column's is its note's title. */
@Composable
private fun TaskBoard.Column<*>.label(): String =
    when (id) {
        "todo" -> stringResource(R.string.board_column_todo)
        "doing" -> stringResource(R.string.board_column_doing)
        "done" -> stringResource(R.string.board_column_done)
        "cancelled" -> stringResource(R.string.board_column_cancelled)
        "overdue" -> stringResource(R.string.board_column_overdue)
        "today" -> stringResource(R.string.board_column_today)
        "tomorrow" -> stringResource(R.string.board_column_tomorrow)
        "week" -> stringResource(R.string.board_column_week)
        "later" -> stringResource(R.string.board_column_later)
        "none" -> stringResource(R.string.board_column_none)
        else -> title
    }

private fun TaskBoard.Grouping.label(): Int =
    when (this) {
        TaskBoard.Grouping.STATUS -> R.string.board_group_status
        TaskBoard.Grouping.DAY -> R.string.board_group_day
        TaskBoard.Grouping.FILE -> R.string.board_group_file
    }

/** Narrows the board to one note. */
@Composable
private fun NoteFilter(
    state: BoardUiState,
    onChoose: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = state.sources.firstOrNull { it.path == state.selectedPath }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                text = chosen?.name ?: stringResource(R.string.tasks_all_files),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = FILTER_LABEL_WIDTH),
            )
            LucideGlyph("chevron-down", size = 16.dp, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tasks_all_files)) },
                onClick = {
                    onChoose(null)
                    open = false
                },
            )
            HorizontalDivider()
            state.sources.forEach { source ->
                DropdownMenuItem(
                    text = {
                        Text(
                            source.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = FILTER_MENU_WIDTH),
                        )
                    },
                    onClick = {
                        onChoose(source.path)
                        open = false
                    },
                    trailingIcon = {
                        Text(
                            source.count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

/** Most of a phone's width, so the next column shows at the edge and says there is one. */
private const val COLUMN_SHARE = 0.86f
private val COLUMN_MAX = 340.dp
private const val COLUMN_WASH = 0.06f
private const val HEADER_WASH = 0.22f
private const val COUNT_ALPHA = 0.18f
private const val CHIP_ALPHA = 0.14f
private val FILTER_LABEL_WIDTH = 120.dp
private val FILTER_MENU_WIDTH = 220.dp
