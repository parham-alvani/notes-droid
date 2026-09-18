package me.parham1995.notes.feature.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.data.TaskBucket
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.ui.AutoDirection
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.theme.Naz

/**
 * Everything open across the whole vault, in the order it is answerable.
 *
 * The vault this was built for keeps tasks in a file per context and a heading
 * per project, which reads well in the editor and answers nothing on a phone:
 * finding out what is late meant opening two dozen files. The same information
 * grouped by date is one screen.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    onOpenNote: (Long) -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Tasks")
                        if (state.total > 0) {
                            Text(
                                text = "${state.total} open",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loading && state.groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing open. Either the vault is quiet or it has not synced yet.",
                    Modifier.padding(32.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            state.groups.forEach { group ->
                stickyHeader(key = "header-${group.bucket.name}") {
                    BucketHeader(group.bucket, group.rows.size)
                }
                items(group.rows, key = { it.id }) { row ->
                    TaskRowView(row, group.bucket) { onOpenNote(row.noteId) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BucketHeader(
    bucket: TaskBucket,
    count: Int,
) {
    // Opaque rather than translucent: it scrolls over the rows beneath it, and
    // a see-through header over a list of text is unreadable.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = bucket.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = bucket.accent(),
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TaskRowView(
    row: TaskRow,
    bucket: TaskBucket,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LucideGlyph(
            name = if (row.state == IN_PROGRESS) "square-dot" else "square",
            modifier = Modifier.padding(top = 2.dp),
            tint = if (row.state == IN_PROGRESS) Naz.Blue else MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = if (row.state == IN_PROGRESS) "in progress" else "open",
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AutoDirection(row.text) {
                Text(
                    text = row.text,
                    style = MaterialTheme.typography.bodyMedium.inScript(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Where it came from, because the same task text turns up under
                // several projects and the file is what disambiguates it.
                Text(
                    text = listOfNotNull(row.noteTitle, row.section.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                row.actionableOn?.let { date ->
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelSmall,
                        color = bucket.accent(),
                    )
                }
            }
        }
    }
}

private const val IN_PROGRESS = "IN_PROGRESS"

private fun TaskBucket.accent(): Color =
    when (this) {
        TaskBucket.OVERDUE -> Naz.Red
        TaskBucket.TODAY -> Naz.Orange
        TaskBucket.TOMORROW -> Naz.VividYellow
        TaskBucket.THIS_WEEK -> Naz.SpringGreen
        TaskBucket.LATER -> Naz.Blue
        TaskBucket.UNDATED -> Naz.Grey
    }
