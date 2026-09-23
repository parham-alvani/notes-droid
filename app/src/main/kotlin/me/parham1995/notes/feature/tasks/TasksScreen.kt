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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.data.TaskBucket
import me.parham1995.notes.data.database.TaskRow
import me.parham1995.notes.ui.AutoDirection
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.text
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
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }

    // Said once and cleared: the same message arriving again on a
    // recomposition would stack a second snackbar on top of the first.
    val message = state.message?.text()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    if (adding) {
        AddTaskDialog(
            state = state,
            onDismiss = { adding = false },
            onAdd = { path, section, text ->
                viewModel.addTask(path, section, text)
                adding = false
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            // Only where there is somewhere to put it. A task needs a file, and
            // offering to add one into a vault the app cannot push to is an
            // offer that fails after the thing has been typed.
            if (state.canWrite && state.sources.isNotEmpty()) {
                FloatingActionButton(onClick = { adding = true }) {
                    LucideGlyph("plus", contentDescription = stringResource(R.string.tasks_add))
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.tasks_title))
                        if (state.total > 0) {
                            Text(
                                text = pluralStringResource(R.plurals.tasks_open_count, state.total, state.total),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Only when there is something to say. A counter that
                        // reads zero most of the time is a counter nobody
                        // reads the rest of the time.
                        if (state.waiting > 0) {
                            Text(
                                text =
                                    pluralStringResource(
                                        R.plurals.tasks_waiting,
                                        state.waiting,
                                        state.waiting,
                                    ),
                                style = MaterialTheme.typography.labelMedium,
                                color = Naz.Orange,
                            )
                        }
                    }
                },
                actions = {
                    // Only worth the chrome once there is something to choose
                    // between. This vault keeps tasks in a file per context,
                    // so the file is the project.
                    if (state.sources.size > 1) {
                        SourceFilter(state, viewModel)
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loading && state.groups.isEmpty()) {
            // It used to offer two explanations and not the right one: "either
            // the vault is quiet or it has not synced yet", said to someone
            // reading a fully synced document archive that simply has no tasks
            // in it. Naming the vault is what makes the empty list make sense,
            // and saying where the tasks actually are is what makes it useful.
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text =
                        if (state.vaultLabel.isBlank()) {
                            stringResource(R.string.tasks_empty)
                        } else {
                            stringResource(R.string.tasks_empty_in, state.vaultLabel)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.elsewhere.forEach { other ->
                    TextButton(onClick = { viewModel.switchTo(other.id) }) {
                        Text(
                            pluralStringResource(
                                R.plurals.tasks_open_in_vault,
                                other.count,
                                other.count,
                                other.label,
                            ),
                        )
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            state.groups.forEach { group ->
                stickyHeader(key = "header-${group.bucket.name}") {
                    BucketHeader(group.bucket, group.rows.size)
                }
                items(group.rows, key = { it.id }) { row ->
                    val complete: (() -> Unit)? =
                        if (state.canWrite) {
                            { viewModel.complete(row) }
                        } else {
                            null
                        }
                    TaskRowView(
                        row = row,
                        bucket = group.bucket,
                        onComplete = complete,
                        onClick = { onOpenNote(row.noteId) },
                    )
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
    onComplete: (() -> Unit)?,
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
            modifier =
                Modifier
                    .padding(top = 2.dp)
                    // The box is the affordance, not the row: tapping the text
                    // opens the note, which is what it has always done, and a
                    // list where a stray tap ticks something off is a list
                    // nobody trusts.
                    .then(
                        onComplete?.let { complete ->
                            Modifier.clickable(onClick = complete).padding(CHECKBOX_TAP)
                        } ?: Modifier,
                    ),
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

/**
 * Narrows the list to one file.
 *
 * The vault this was built for keeps tasks in a file per context -- one for
 * each job, one for the flat, one for the course -- so filtering by file is
 * filtering by which part of a life is being looked at. The count beside each
 * name is what makes it possible to choose without opening them one by one.
 */
@Composable
private fun SourceFilter(
    state: TasksUiState,
    viewModel: TasksViewModel,
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
                    viewModel.filterBy(null)
                    open = false
                },
                trailingIcon = {
                    Text(
                        state.sources.sumOf { it.count }.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        viewModel.filterBy(source.path)
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

private val FILTER_LABEL_WIDTH = 120.dp
private val FILTER_MENU_WIDTH = 220.dp

/** Widens the checkbox to something a thumb can hit without opening the note. */
private val CHECKBOX_TAP = 6.dp

/**
 * Adds a task to a file that already has some.
 *
 * File and project are offered from what the vault already contains rather than
 * typed: this vault keeps one file per context and one heading per project, and
 * a task filed under a heading that does not quite match an existing one is a
 * task nobody finds again.
 */
@Composable
private fun AddTaskDialog(
    state: TasksUiState,
    onDismiss: () -> Unit,
    onAdd: (path: String, section: String, text: String) -> Unit,
) {
    var path by remember { mutableStateOf(state.selectedPath ?: state.sources.first().path) }
    var section by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasks_add)) },
        confirmButton = {
            TextButton(onClick = { onAdd(path, section, text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.tasks_add_text)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ChoiceField(
                    label = stringResource(R.string.tasks_add_file),
                    value = state.sources.firstOrNull { it.path == path }?.name ?: path,
                    options = state.sources.map { it.name to it.path },
                    onChoose = { chosen ->
                        path = chosen
                        // A project heading belongs to a file, so it cannot
                        // survive the file changing underneath it.
                        section = ""
                    },
                )
                ChoiceField(
                    label = stringResource(R.string.tasks_add_section),
                    value = section.ifBlank { stringResource(R.string.tasks_add_section_end) },
                    options = state.sectionsByPath[path].orEmpty().map { it to it },
                    onChoose = { section = it },
                )
            }
        },
    )
}

/** A read-only field that opens a menu, for choosing from what exists. */
@Composable
private fun ChoiceField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onChoose: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            TextButton(onClick = { open = true }, enabled = options.isNotEmpty()) {
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                LucideGlyph("chevron-down", size = 16.dp, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (name, chosen) ->
                    DropdownMenuItem(
                        text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onChoose(chosen)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
