package me.parham1995.notes.feature.browser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.parham1995.notes.ui.ItemRow
import me.parham1995.notes.ui.VaultRowItem
import me.parham1995.notes.ui.pdf.PdfViewer
import me.parham1995.notes.ui.render.Attachments
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onOpenNote: (Long) -> Unit,
    initialPath: String = "",
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Only on arrival. Keying on the argument rather than running every
    // composition means walking up the tree afterwards is not undone on the
    // next recomposition.
    LaunchedEffect(initialPath) { if (initialPath.isNotEmpty()) viewModel.open(initialPath) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A PDF is read here; anything else goes to whatever app owns that type.
    var reading by remember { mutableStateOf<File?>(null) }

    val openAttachment: (String) -> Unit = { path ->
        scope.launch {
            val file = viewModel.attachment(path)
            val message =
                when {
                    file == null -> "Could not fetch " + path.substringAfterLast('/')
                    Attachments.isPdf(path) -> {
                        reading = file
                        null
                    }
                    Attachments.open(context, file) -> null
                    else -> "Nothing on this phone opens that file"
                }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    reading?.let { file ->
        PdfViewer(
            file = file,
            title = file.name,
            onDismiss = { reading = null },
            onOpenExternally = {
                reading = null
                if (!Attachments.open(context, file)) {
                    Toast.makeText(context, "Nothing on this phone opens that file", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    // Inside a folder, back walks up the tree before it leaves the screen.
    BackHandler(enabled = state.path.isNotEmpty()) { viewModel.up() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.path.substringAfterLast('/').ifEmpty { "Vault" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (state.path.isNotEmpty()) {
                        IconButton(onClick = { viewModel.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                    }
                },
                actions = {
                    // Pull-to-refresh alone is invisible until you already know
                    // it is there, which makes the app look like it cannot sync.
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.syncing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                state.syncProgress?.let { (done, total) ->
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Syncing $done of $total",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                // At depth seven a plain title says nothing about where you are.
                if (state.crumbs.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Crumb("Vault") { viewModel.open("") }
                        state.crumbs.forEach { (name, target) ->
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                            Crumb(name) { viewModel.open(target) }
                        }
                    }
                    HorizontalDivider()
                }

                LazyColumn(Modifier.fillMaxSize()) {
                    if (state.path.isEmpty() && state.recent.isNotEmpty()) {
                        item {
                            SectionLabel("Recently opened")
                        }
                        items(state.recent, key = { "recent-${it.note.id}" }) { row ->
                            ItemRow(
                                title = row.note.title.ifBlank { row.note.name },
                                subtitle = row.note.path.substringBeforeLast('/', ""),
                                icon = row.icon,
                                defaultIcon = "file-text",
                                iconDescription = "Note",
                                onClick = { onOpenNote(row.note.id) },
                            )
                        }
                        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                        item { SectionLabel("All notes - ${state.noteCount}") }
                    }

                    items(state.items, key = { it.item.path }) { row ->
                        BrowserRow(row, viewModel, onOpenNote, openAttachment)
                    }

                    if (!state.loading && state.items.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "Nothing here yet. Pull down to sync.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(
    row: VaultRowItem,
    viewModel: BrowserViewModel,
    onOpenNote: (Long) -> Unit,
    onOpenAttachment: (String) -> Unit,
) {
    val item = row.item
    ItemRow(
        title = item.name,
        icon = row.icon,
        defaultIcon =
            when {
                item.isFolder -> "folder"
                item.isAttachment -> Attachments.iconOf(item.path)
                else -> "file-text"
            },
        iconDescription =
            when {
                item.isFolder -> "Folder"
                item.isAttachment -> "File"
                else -> "Note"
            },
        // A folder with its own note opens that note; the chevron descends.
        underline = item.isFolder && item.noteId != null,
        onClick = {
            val note = item.noteId
            when {
                note != null -> onOpenNote(note)
                item.isAttachment -> onOpenAttachment(item.path)
                else -> viewModel.open(item.path)
            }
        },
        trailing =
            if (!item.isFolder) {
                null
            } else {
                {
                    IconButton(onClick = { viewModel.open(item.path) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open folder",
                        )
                    }
                }
            },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Crumb(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
