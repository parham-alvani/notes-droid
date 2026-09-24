package me.parham1995.notes.feature.tags

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.parham1995.notes.R
import me.parham1995.notes.data.database.NoteEntity
import me.parham1995.notes.markdown.TagTreeNode
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript

/**
 * Every tag in a vault, and the notes under one.
 *
 * Reached two ways: from the browser, which opens the tree, and from a tag
 * tapped in a note, which opens straight on that tag's notes. A parent tag
 * lists its children's notes as well, as Obsidian's tag pane does -- a note
 * filed under `project/alpha` is a `project` note.
 */
@Composable
fun TagsScreen(
    tag: String,
    vaultId: Long,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    viewModel: TagsViewModel = hiltViewModel(),
) {
    LaunchedEffect(tag, vaultId) { viewModel.start(vaultId, tag) }
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    // Opened on the tree, back from a tag returns to the tree. Opened on a
    // tag from a note, back returns to the note.
    val backToTree = selected != null && tag.isBlank()
    BackHandler(enabled = backToTree) { viewModel.select(null) }

    TagsContent(
        rows = rows,
        selected = selected,
        onBack = { if (backToTree) viewModel.select(null) else onBack() },
        onSelect = viewModel::select,
        onOpenNote = onOpenNote,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagsContent(
    rows: List<Pair<TagTreeNode, Int>>?,
    selected: TaggedNotes?,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenNote: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selected?.let { "#" + it.tag } ?: stringResource(R.string.tags_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                selected != null && selected.loading || selected == null && rows == null ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                selected != null -> NoteList(selected.notes, onOpenNote)

                rows.isNullOrEmpty() ->
                    Text(
                        text = stringResource(R.string.tags_empty),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium.inScript(),
                    )

                else -> TagList(rows, onSelect)
            }
        }
    }
}

@Composable
private fun TagList(
    rows: List<Pair<TagTreeNode, Int>>,
    onSelect: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.first.path }) { (node, depth) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(node.path) }
                    .padding(start = 16.dp + INDENT * depth, end = 16.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideGlyph(if (depth == 0) "tag" else "hash", size = 18.dp)
                Text(
                    text = node.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.inScript(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = node.count.toString(),
                    style = MaterialTheme.typography.labelMedium.inScript(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoteList(
    notes: List<NoteEntity>,
    onOpenNote: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(notes, key = { it.id }) { note ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenNote(note.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = note.title.ifBlank { note.name },
                    style = MaterialTheme.typography.bodyLarge.inScript(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.parent.isNotEmpty()) {
                    Text(
                        text = note.parent,
                        style = MaterialTheme.typography.bodySmall.inScript(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val INDENT = 16.dp
