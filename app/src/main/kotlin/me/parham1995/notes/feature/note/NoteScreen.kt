package me.parham1995.notes.feature.note

import android.content.ClipData
import android.content.Intent
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.parham1995.notes.ui.ItemRow
import me.parham1995.notes.ui.VaultRowItem
import me.parham1995.notes.ui.icon.VaultIcon
import me.parham1995.notes.ui.render.InlineActions
import me.parham1995.notes.ui.render.MarkdownDocument
import me.parham1995.notes.ui.render.RenderActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    noteId: Long,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenFolder: (String) -> Unit,
    viewModel: NoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showOutline by remember { mutableStateOf(false) }
    var showBacklinks by remember { mutableStateOf(false) }
    // Reset per note, so following a link from a folder note's contents does
    // not land on the next note with the wrong tab selected.
    var showContents by remember(noteId) { mutableStateOf(false) }

    LaunchedEffect(noteId) { viewModel.load(noteId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // The vault turns Iconic's title icons on, so a note
                        // that has one carries it into its own header too.
                        state.icon?.let { VaultIcon(spec = it, default = "file-text") }
                        Text(
                            text = state.note?.title ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showOutline = true }, enabled = state.note != null) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Outline")
                    }
                    // The count is more use than an icon here: it says
                    // whether opening the sheet is worth it.
                    if (state.backlinks.isNotEmpty()) {
                        TextButton(onClick = { showBacklinks = true }) {
                            Text("${state.backlinks.size} links")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // A folder note is only half of what a folder is: the page someone
            // wrote, and the things actually in it. Obsidian shows both at
            // once, in the editor and the sidebar; on a phone there is only
            // one pane, so they take turns.
            if (state.isFolderNote) {
                PrimaryTabRow(selectedTabIndex = if (showContents) 1 else 0) {
                    Tab(
                        selected = !showContents,
                        onClick = { showContents = false },
                        text = { Text("Note") },
                    )
                    Tab(
                        selected = showContents,
                        onClick = { showContents = true },
                        // The count is the useful part: it says whether the
                        // folder holds anything the note does not mention.
                        text = { Text("Contents · ${state.contents.size}") },
                    )
                }
            }

            if (showContents) {
                FolderContents(state.contents, onOpenNote, onOpenFolder)
            } else {
                Box(Modifier.fillMaxSize()) {
                    when {
                        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.missing ->
                            Text(
                                "This note is not on the device.",
                                Modifier.align(Alignment.Center).padding(24.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )

                        else ->
                            state.note?.let { note ->
                                MarkdownDocument(
                                    blocks = note.blocks,
                                    brokenLinks = state.brokenLinks,
                                    listState = listState,
                                    actions =
                                        RenderActions(
                                            inline =
                                                InlineActions(
                                                    onWikiLink = { target, _ ->
                                                        viewModel.targetOf(target)?.let(onOpenNote)
                                                    },
                                                    onExternalLink = { url ->
                                                        runCatching {
                                                            context.startActivity(
                                                                Intent(Intent.ACTION_VIEW, url.toUri()),
                                                            )
                                                        }
                                                    },
                                                ),
                                            onCopyCode = { code ->
                                                scope.launch {
                                                    clipboard.setClipEntry(
                                                        ClipData.newPlainText("code", code).toClipEntry(),
                                                    )
                                                }
                                            },
                                        ),
                                )
                            }
                    }
                }
            }
        }
    }

    if (showOutline) {
        ModalBottomSheet(onDismissRequest = { showOutline = false }) {
            val headings = state.note?.headings.orEmpty()
            if (headings.isEmpty()) {
                Text("No headings", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn {
                    items(headings, key = { it.id }) { heading ->
                        Text(
                            text = heading.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showOutline = false
                                        scope.launch { listState.animateScrollToItem(heading.blockIndex) }
                                    }
                                    // Indent by level so the outline reads as a
                                    // structure rather than a flat list.
                                    .padding(start = (12 + (heading.level - 1) * 12).dp, end = 16.dp)
                                    .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    if (showBacklinks) {
        ModalBottomSheet(onDismissRequest = { showBacklinks = false }) {
            LazyColumn {
                items(state.backlinks, key = { it.noteId.toString() + it.context }) { row ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                showBacklinks = false
                                onOpenNote(row.noteId)
                            }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(row.title, style = MaterialTheme.typography.bodyMedium)
                        // The stored context line -- backlinks never re-read
                        // the source note to show it.
                        Text(
                            text = row.context,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = row.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * What the folder actually holds.
 *
 * A subfolder opens its own landing page when it has one, which is the same
 * rule the browser follows. When it has none there is no note to show, so it
 * hands off to the browser at that path rather than pretending otherwise.
 */
@Composable
private fun FolderContents(
    rows: List<VaultRowItem>,
    onOpenNote: (Long) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        Text(
            "This folder holds nothing but its own note.",
            Modifier.fillMaxWidth().padding(24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.item.path }) { row ->
            val item = row.item
            ItemRow(
                title = item.name,
                icon = row.icon,
                defaultIcon = if (item.isFolder) "folder" else "file-text",
                iconDescription = if (item.isFolder) "Folder" else "Note",
                underline = item.isFolder && item.noteId != null,
                onClick = {
                    val note = item.noteId
                    when {
                        note != null -> onOpenNote(note)
                        else -> onOpenFolder(item.path)
                    }
                },
            )
        }
    }
}
