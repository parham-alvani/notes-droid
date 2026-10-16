package me.parham1995.notes.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import me.parham1995.notes.R
import me.parham1995.notes.data.database.QueryTaskRow
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.QueryTask
import me.parham1995.notes.markdown.TaskState
import me.parham1995.notes.markdown.isFinished
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript

/** What a ` ```tasks ` query answered, and the lines of it that were not read. */
data class TasksQueryResult(
    val rows: List<QueryTaskRow>,
    val notUnderstood: List<String>,
    /** Each row's groups, outermost first, in step with [rows]; empty when ungrouped. */
    val groups: List<List<String>> = emptyList(),
)

/** How the index's stored state reads as a [QueryTask]. */
fun QueryTaskRow.toQueryTask(): QueryTask =
    QueryTask(
        text = text,
        state = state.toTaskState(),
        path = notePath,
        section = section,
        due = due,
        scheduled = scheduled,
        done = done,
        recurring = recurring,
    )

private fun String.toTaskState(): TaskState = TaskState.entries.firstOrNull { it.name == this } ?: TaskState.UNCHECKED

/**
 * A ` ```tasks ` query, answered: the tasks it selects, each a tap away from
 * the note it is written in, and a box that ticks it where the vault can be
 * written to.
 *
 * A line the query language here does not cover is said under the list, not
 * dropped quietly -- a filter left out lists tasks the query was written to
 * leave out.
 */
@Composable
internal fun TasksQueryView(
    block: MdBlock.TasksQuery,
    actions: RenderActions,
    modifier: Modifier = Modifier,
) {
    val load = actions.queryTasks
    if (load == null) {
        // Embedded in another note: whose placeholders, and whose vault, would
        // be a guess. The note itself answers it.
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = stringResource(R.string.unsupported_block, stringResource(R.string.unsupported_tasks_query)),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium.inScript(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val flow: Flow<TasksQueryResult> = remember(block.query, load) { load(block.query) }
    val result by flow.collectAsStateWithLifecycle(initialValue = null)
    val reading = LocalReading.current
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, outline, RoundedCornerShape(12.dp))
            .testTag("tasks-query"),
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
                name = "list-checks",
                size = HEADER_ICON * reading.textScale,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.tasks_title),
                style = MaterialTheme.typography.labelLarge.inScript(),
                modifier = Modifier.weight(1f),
            )
            result?.let {
                Text(
                    text = it.rows.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val answered = result ?: return@Column
        Column(Modifier.padding(vertical = 4.dp)) {
            if (answered.rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.tasks_query_empty),
                    style = MaterialTheme.typography.bodyMedium.inScript(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            var previous: List<String> = emptyList()
            answered.rows.take(MAX_SHOWN).forEachIndexed { index, row ->
                val groups = answered.groups.getOrNull(index).orEmpty()
                // A heading for each level that changed here, and for every
                // level inside it -- a new file starts its headings afresh.
                val changed = groups.indices.firstOrNull { groups[it] != previous.getOrNull(it) }
                changed?.let { from ->
                    for (level in from until groups.size) {
                        groups[level].takeIf { it.isNotBlank() }?.let { GroupHeading(it, level) }
                    }
                }
                previous = groups
                QueryTaskLine(row, actions)
            }
            val hidden = answered.rows.size - MAX_SHOWN
            if (hidden > 0) {
                Text(
                    text = pluralStringResource(R.plurals.tasks_query_hidden, hidden, hidden),
                    style = MaterialTheme.typography.labelMedium.inScript(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            answered.notUnderstood.forEach { line ->
                Text(
                    text = stringResource(R.string.tasks_query_not_understood, line),
                    style = MaterialTheme.typography.labelMedium.inScript(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupHeading(
    name: String,
    level: Int,
) {
    Text(
        text = name,
        style =
            (if (level == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge)
                .inScript(),
        color = if (level == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp + GROUP_INDENT * level, end = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun QueryTaskLine(
    row: QueryTaskRow,
    actions: RenderActions,
) {
    val state = row.state.toTaskState()
    val open = !state.isFinished
    val complete = actions.onCompleteQueryTask?.takeIf { open && row.line >= 0 }
    val haptics = LocalHapticFeedback.current
    val scale = LocalReading.current.textScale
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // The note, at the heading the task sits under.
                .clickable(role = Role.Button) {
                    actions.inline.onNoteLink(row.noteId, row.section.takeIf { it.isNotBlank() })
                }.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The box takes its own tap, so ticking a task does not also open it.
        // No minimumInteractiveComponentSize, for the reason the note's own
        // boxes have none: Compose already gives it a 48dp target.
        Box(
            modifier =
                Modifier
                    .padding(top = 2.dp * scale)
                    .semantics {
                        toggleableState =
                            when (state) {
                                TaskState.CHECKED -> ToggleableState.On
                                TaskState.CANCELLED -> ToggleableState.Indeterminate
                                else -> ToggleableState.Off
                            }
                    }.clickable(enabled = complete != null, role = Role.Checkbox) {
                        complete?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            it(row)
                        }
                    },
        ) {
            LucideGlyph(
                name =
                    when (state) {
                        TaskState.CHECKED -> "square-check-big"
                        TaskState.CANCELLED -> "square-x"
                        TaskState.IN_PROGRESS -> "square-dot"
                        else -> "square"
                    },
                size = BOX_ICON * scale,
                tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = state.name.lowercase().replace('_', ' '),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = row.text,
                style = MaterialTheme.typography.bodyMedium.inScript(),
                color = if (open) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = if (state == TaskState.CHECKED) TextDecoration.LineThrough else null,
            )
            val date = row.due ?: row.scheduled
            Text(
                text = listOfNotNull(row.noteTitle.ifBlank { row.notePath }, date).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall.inScript(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The query is one item of the note's lazy list, so every row in it is
 * composed at once; a query that selects the whole vault would stall a frame.
 */
private const val MAX_SHOWN = 150
private val HEADER_ICON = 18.dp
private val BOX_ICON = 16.dp
private val GROUP_INDENT = 8.dp
