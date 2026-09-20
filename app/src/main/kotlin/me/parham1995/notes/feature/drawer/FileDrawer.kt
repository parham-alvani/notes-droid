package me.parham1995.notes.feature.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
import me.parham1995.notes.ui.ItemRow
import me.parham1995.notes.ui.icon.LucideGlyph

/**
 * Somewhere else to go, without leaving where you are.
 *
 * The reader had one way to reach another file: back out to the browser, which
 * loses the note on screen and the place in it. That is fine for starting a
 * session and wrong for the thing this vault is actually used for -- following
 * a thought across four notes and back.
 *
 * What it offers, in the order the answer is usually in: type a name; the tabs
 * already open; the last few notes; and the folder this note sits in, walked a
 * level at a time. Nothing here is a second browser -- the browser is one tap
 * away and does the whole tree properly.
 */
@Composable
fun FileDrawerSheet(
    state: FileDrawerUiState,
    viewModel: FileDrawerViewModel,
    onOpenNote: (Long) -> Unit,
    onOpenNoteInNewTab: (Long) -> Unit,
    onBrowseFolder: (String) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            VaultHeader(state, viewModel, onBrowseFolder)
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.drawer_filter)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                trailingIcon = {
                    if (state.filtering) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            LucideGlyph("x", size = 18.dp, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        LazyColumn(Modifier.fillMaxWidth()) {
            if (state.filtering) {
                if (state.matches.isEmpty()) {
                    item { Empty(stringResource(R.string.search_no_match)) }
                }
                items(state.matches, key = { "match-${it.id}" }) { note ->
                    NoteRow(note, onOpenNote, onOpenNoteInNewTab)
                }
                return@LazyColumn
            }

            if (state.tabs.size > 1) {
                item { SectionLabel(stringResource(R.string.drawer_open)) }
                items(state.tabs, key = { "tab-${it.index}-${it.noteId}" }) { tab ->
                    TabRow(tab, viewModel)
                }
            }

            if (state.recent.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.drawer_recent)) }
                items(state.recent, key = { "recent-${it.id}" }) { note ->
                    NoteRow(note, onOpenNote, onOpenNoteInNewTab)
                }
            }

            item { SectionLabel(state.folder.ifEmpty { stringResource(R.string.drawer_root) }) }
            state.parent?.let { up ->
                item {
                    ItemRow(
                        title = up.substringAfterLast('/').ifEmpty { stringResource(R.string.drawer_root) },
                        icon = null,
                        defaultIcon = "corner-left-up",
                        onClick = { viewModel.openFolder(up) },
                    )
                }
            }
            items(state.items, key = { "item-${it.item.path}" }) { row ->
                val item = row.item
                ItemRow(
                    title = item.name,
                    icon = row.icon,
                    defaultIcon = if (item.isFolder) "folder" else "file-text",
                    // A folder opens in the drawer; its own note, when it has
                    // one, is what holding it asks for. The browser is where
                    // the whole tree lives, and it is one tap up in the header.
                    onClick = {
                        when {
                            item.isFolder -> viewModel.openFolder(item.path)
                            item.noteId != null -> onOpenNote(item.noteId!!)
                            else -> Unit
                        }
                    },
                    onLongClick = {
                        item.noteId?.let { if (item.isFolder) onOpenNote(it) else onOpenNoteInNewTab(it) }
                    },
                    underline = item.isFolder && item.noteId != null,
                )
            }
            if (state.items.isEmpty()) {
                item { Empty(stringResource(R.string.drawer_folder_empty)) }
            }
        }
    }
}

/** Which vault this is, and a way into the browser for the whole tree. */
@Composable
private fun VaultHeader(
    state: FileDrawerUiState,
    viewModel: FileDrawerViewModel,
    onBrowseFolder: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box {
            TextButton(onClick = { open = true }, enabled = state.vaults.size > 1) {
                Text(
                    text = state.vaultLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.vaults.size > 1) {
                    LucideGlyph("chevron-down", size = 16.dp, contentDescription = null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.vaults.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            viewModel.switchVault(id)
                            open = false
                        },
                    )
                }
            }
        }
        IconButton(onClick = { onBrowseFolder(state.folder) }) {
            LucideGlyph(
                "folder-tree",
                size = 20.dp,
                contentDescription = stringResource(R.string.drawer_browse_all),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun NoteRow(
    note: DrawerNote,
    onOpenNote: (Long) -> Unit,
    onOpenNoteInNewTab: (Long) -> Unit,
) {
    ItemRow(
        title = note.title,
        icon = note.icon,
        defaultIcon = "file-text",
        // The folder underneath, because this vault has 110 duplicated
        // basenames in it and a list of titles alone cannot be chosen from.
        subtitle = note.folder,
        onClick = { onOpenNote(note.id) },
        onLongClick = { onOpenNoteInNewTab(note.id) },
    )
}

/** One open tab: switch to it, or shut it. */
@Composable
private fun TabRow(
    tab: DrawerTab,
    viewModel: FileDrawerViewModel,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { viewModel.selectTab(tab.index) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LucideGlyph(
            name = if (tab.active) "dot" else "file-text",
            size = 20.dp,
            tint =
                if (tab.active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            contentDescription = null,
        )
        Text(
            text = tab.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (tab.active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { viewModel.closeTab(tab.index) }) {
            LucideGlyph("x", size = 16.dp, contentDescription = stringResource(R.string.action_close))
        }
    }
}
