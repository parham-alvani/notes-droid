package me.parham1995.notes.feature.note

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.ui.AutoDirection
import me.parham1995.notes.ui.ItemRow
import me.parham1995.notes.ui.VaultRowItem
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.icon.VaultIcon
import me.parham1995.notes.ui.image.ImageViewer
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.pdf.PdfViewer
import me.parham1995.notes.ui.render.Attachments
import me.parham1995.notes.ui.render.InlineActions
import me.parham1995.notes.ui.render.MarkdownDocument
import me.parham1995.notes.ui.render.RenderActions
import me.parham1995.notes.ui.theme.Markup
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    noteId: Long,
    openInNewTab: Boolean,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenGraph: (Long) -> Unit = {},
    viewModel: NoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nothingOpens = stringResource(R.string.error_nothing_opens)
    val noteMissing = stringResource(R.string.note_missing)
    val couldNotFetch = stringResource(R.string.error_could_not_fetch)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showOutline by remember { mutableStateOf(false) }
    var showBacklinks by remember { mutableStateOf(false) }
    // Reset per note, so following a link from a folder note's contents does
    // not land on the next note with the wrong tab selected.
    var showContents by remember(noteId) { mutableStateOf(false) }
    // A PDF opens in place; everything else is handed to another app.
    var reading by remember(noteId) { mutableStateOf<File?>(null) }
    // The embedded image being looked at full screen, by its vault path.
    var zoomed by remember(noteId) { mutableStateOf<Pair<String, String?>?>(null) }
    var finding by remember(noteId) { mutableStateOf(false) }
    var peeking by remember { mutableStateOf<LinkTarget?>(null) }
    var peekBroken by remember { mutableStateOf<String?>(null) }
    // A heading to land on once the note it belongs to has loaded.
    var pendingHeading by remember { mutableStateOf<String?>(null) }

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    // The route argument only ever seeds the set. After that the screen
    // follows whichever tab is being read, so switching tabs does not have to
    // navigate and lose the back stack.
    LaunchedEffect(noteId) { viewModel.openTab(noteId, inNewTab = openInNewTab) }
    val activeId = tabs.current?.noteId ?: noteId

    LaunchedEffect(activeId) { viewModel.load(activeId) }

    // Back retraces this tab, not the app.
    //
    // Each tab keeps its own trail, so back goes to the note this one came
    // from rather than to whatever was opened last anywhere. With nothing
    // behind it in this tab the gesture falls through and leaves the reader,
    // which is the only way out; the button says so by being disabled.
    BackHandler(enabled = tabs.current?.canGoBack == true) { viewModel.back() }
    LaunchedEffect(state.note?.id, state.note?.title) {
        val note = state.note
        if (note != null) viewModel.retitleTab(note.id, note.title)
    }

    // Resume where this note was left. Keyed on the note's own id rather than
    // the argument, so it runs once the note has actually loaded.
    val loadedId = state.note?.id
    LaunchedEffect(loadedId, pendingHeading) {
        val note = state.note ?: return@LaunchedEffect
        // A heading asked for wins over where the note was left: it is the
        // reason the note was opened at all.
        val target = blockForHeading(note.headings, pendingHeading)
        when {
            target != null -> {
                listState.scrollToItem(target)
                pendingHeading = null
            }
            pendingHeading != null -> pendingHeading = null // renamed since; stop asking
            note.scrollIndex > 0 -> listState.scrollToItem(note.scrollIndex)
        }
    }
    DisposableEffect(loadedId) {
        onDispose {
            if (loadedId != null) viewModel.rememberScroll(listState.firstVisibleItemIndex)
        }
    }

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
                    // Disabled rather than hidden when this tab has nowhere
                    // to go back to: a button that moves you somewhere
                    // unrelated is worse than one that plainly cannot.
                    IconButton(
                        onClick = { viewModel.back() },
                        enabled = tabs.current?.canGoBack == true,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    // Shares the note as it is written, not as it is rendered:
                    // what goes out is markdown, which is what the person on
                    // the other end can do something with.
                    IconButton(
                        onClick = {
                            scope.launch {
                                val text = viewModel.markdown()
                                if (text == null) {
                                    Toast
                                        .makeText(
                                            context,
                                            noteMissing,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                } else {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/markdown"
                                                putExtra(Intent.EXTRA_TITLE, state.note?.title)
                                                putExtra(Intent.EXTRA_SUBJECT, state.note?.title)
                                                putExtra(Intent.EXTRA_TEXT, text)
                                            },
                                            state.note?.title,
                                        ),
                                    )
                                }
                            }
                        },
                        enabled = state.note != null,
                    ) {
                        LucideGlyph("share-2", size = 20.dp, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(
                        onClick = {
                            finding = !finding
                            if (!finding) viewModel.clearFind()
                        },
                        enabled = state.note != null,
                    ) {
                        LucideGlyph(
                            if (finding) "x" else "text-search",
                            size = 20.dp,
                            contentDescription = if (finding) "Close find" else stringResource(R.string.note_find),
                        )
                    }
                    IconButton(
                        onClick = { state.note?.id?.let(onOpenGraph) },
                        enabled = state.note != null,
                    ) {
                        LucideGlyph(
                            "waypoints",
                            size = 20.dp,
                            contentDescription = stringResource(R.string.note_connections),
                        )
                    }
                    IconButton(onClick = { showOutline = true }, enabled = state.note != null) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.note_outline))
                    }
                    // The count is more use than an icon here: it says
                    // whether opening the sheet is worth it.
                    if (state.backlinks.isNotEmpty()) {
                        TextButton(onClick = { showBacklinks = true }) {
                            Text(pluralStringResource(R.plurals.note_links, state.backlinks.size, state.backlinks.size))
                        }
                    }
                },
            )
        },
    ) { padding ->
        // One column, not two siblings.
        //
        // A Scaffold lays its content slot out as a box, so a strip and a
        // full-height column emitted beside each other are drawn on top of one
        // another: the strip sat behind the note's first lines and the column
        // swallowed every tap meant for it, which reads as a tab bar that
        // collides with the text and does not work.
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            // Only with something to switch between: one tab is a strip that
            // says the same thing as the title above it.
            if (tabs.tabs.size > 1) {
                TabStrip(
                    tabs = tabs,
                    onSelect = viewModel::selectTab,
                    onClose = { index -> if (!viewModel.closeTab(index)) onBack() },
                )
                HorizontalDivider()
            }
            Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                // A folder note is only half of what a folder is: the page someone
                // wrote, and the things actually in it. Obsidian shows both at
                // once, in the editor and the sidebar; on a phone there is only
                // one pane, so they take turns.
                if (state.isFolderNote) {
                    PrimaryTabRow(selectedTabIndex = if (showContents) 1 else 0) {
                        Tab(
                            selected = !showContents,
                            onClick = { showContents = false },
                            text = { Text(stringResource(R.string.note_kind)) },
                        )
                        Tab(
                            selected = showContents,
                            onClick = { showContents = true },
                            // The count is the useful part: it says whether the
                            // folder holds anything the note does not mention.
                            text = { Text(stringResource(R.string.note_contents, state.contents.size)) },
                        )
                    }
                }

                if (finding) {
                    FindBar(
                        query = state.findQuery,
                        matches = state.matches.size,
                        onQueryChange = viewModel::find,
                        onJump = { index -> scope.launch { listState.animateScrollToItem(index) } },
                        matchBlocks = state.matches.map { it.blockIndex },
                    )
                }

                if (showContents) {
                    FolderContents(state.contents, onOpenNote, onOpenFolder)
                } else {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            state.missing ->
                                Text(
                                    stringResource(R.string.note_not_on_device),
                                    Modifier.align(Alignment.Center).padding(24.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )

                            else ->
                                state.note?.let { note ->
                                    MarkdownDocument(
                                        blocks = note.blocks,
                                        brokenLinks = state.brokenLinks,
                                        listState = listState,
                                        onPinch = viewModel::pinchTextScale,
                                        actions =
                                            RenderActions(
                                                vaultId = note.vaultId,
                                                inline =
                                                    InlineActions(
                                                        // A look before a leap.
                                                        // Following a link to
                                                        // find it was not the one
                                                        // you meant costs a load
                                                        // and the place you were
                                                        // reading.
                                                        onWikiLink = { target, heading ->
                                                            val id = viewModel.targetOf(target)
                                                            when {
                                                                // `[[#Heading]]` means this
                                                                // note, so there is nothing
                                                                // to decide about.
                                                                target.isBlank() -> pendingHeading = heading
                                                                id == null -> peekBroken = target
                                                                else ->
                                                                    scope.launch {
                                                                        peeking =
                                                                            viewModel
                                                                                .peek(id)
                                                                                ?.copy(heading = heading)
                                                                    }
                                                            }
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
                                                // Never wired until now: the card was drawn, said
                                                // "open with another app", and did nothing at all
                                                // when tapped.
                                                // Never wired either: images were
                                                // drawn, took a tap, and did
                                                // nothing with it.
                                                onImage = { path, alt -> zoomed = path to alt },
                                                onAttachment = { path ->
                                                    scope.launch {
                                                        val file = viewModel.attachment(path)
                                                        val message =
                                                            when {
                                                                file == null ->
                                                                    couldNotFetch.format(path.substringAfterLast('/'))
                                                                // A PDF is read here; everything else
                                                                // belongs to whatever app owns that type.
                                                                Attachments.isPdf(path) -> {
                                                                    reading = file
                                                                    null
                                                                }
                                                                Attachments.open(context, file) -> null
                                                                else -> nothingOpens
                                                            }
                                                        message?.let {
                                                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                                        }
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
    }

    // Both are needed: an image belongs to the vault of the note embedding it,
    // and there is nothing to show once that note has gone.
    state.note?.let { note ->
        zoomed?.let { (path, alt) ->
            ImageViewer(
                vaultId = note.vaultId,
                path = path,
                alt = alt,
                onDismiss = { zoomed = null },
                onOpenExternally = {
                    scope.launch {
                        val file = viewModel.attachment(path)
                        val opened = file != null && Attachments.open(context, file)
                        if (!opened) {
                            Toast
                                .makeText(
                                    context,
                                    nothingOpens,
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                        zoomed = null
                    }
                },
            )
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
                    Toast.makeText(context, nothingOpens, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    peeking?.let { target ->
        LinkPeek(
            target = target,
            onDismiss = { peeking = null },
            onOpenHere = {
                peeking = null
                pendingHeading = target.heading
                viewModel.openTab(target.noteId, inNewTab = false)
            },
            onOpenInNewTab = {
                peeking = null
                pendingHeading = target.heading
                viewModel.openTab(target.noteId, inNewTab = true)
            },
        )
    }

    peekBroken?.let { target ->
        BrokenLinkPeek(target = target, onDismiss = { peekBroken = null })
    }

    if (showOutline) {
        ModalBottomSheet(onDismissRequest = { showOutline = false }) {
            val headings = state.note?.headings.orEmpty()
            if (headings.isEmpty()) {
                Text(
                    stringResource(R.string.note_no_headings),
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
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
            // Looked up when the sheet opens rather than with the note: it is a
            // full-text search, and most notes are read without anyone asking.
            LaunchedEffect(noteId) { viewModel.loadMentions() }

            LazyColumn {
                if (state.backlinks.isNotEmpty()) {
                    item { SheetLabel("Linked from ${state.backlinks.size}") }
                }
                items(state.backlinks, key = { "link-" + it.noteId + it.context }) { row ->
                    ReferenceRow(
                        title = row.title,
                        // The stored context line -- backlinks never re-read
                        // the source note to show it.
                        context = AnnotatedString(row.context),
                        path = row.path,
                        onClick = {
                            showBacklinks = false
                            onOpenNote(row.noteId)
                        },
                    )
                }

                // Obsidian calls these unlinked mentions: notes that say this
                // one's name in prose and never turned it into a link. In a
                // vault where every link is typed by hand, that is where the
                // connections somebody meant to make actually are.
                if (state.mentions.isNotEmpty()) {
                    item { SheetLabel("Mentioned in ${state.mentions.size}, not linked") }
                }
                items(state.mentions, key = { "mention-" + it.noteId }) { hit ->
                    ReferenceRow(
                        title = hit.title,
                        context = hit.snippet.highlighted(),
                        path = hit.path,
                        onClick = {
                            showBacklinks = false
                            onOpenNote(hit.noteId)
                        },
                    )
                }

                if (state.mentionsLoaded && state.backlinks.isEmpty() && state.mentions.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.note_no_mentions),
                            Modifier.fillMaxWidth().padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
    onOpenGraph: (Long) -> Unit = {},
) {
    if (rows.isEmpty()) {
        Text(
            stringResource(R.string.note_folder_empty),
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
                iconDescription = if (item.isFolder) "Folder" else stringResource(R.string.note_kind),
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

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One reference to this note: a link that points here, or a mention that does not. */
@Composable
private fun ReferenceRow(
    title: String,
    context: AnnotatedString,
    path: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        AutoDirection(context.text) {
            Text(
                text = context,
                style = MaterialTheme.typography.bodySmall.inScript(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = path,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    HorizontalDivider()
}

/**
 * The excerpt FTS5 hands back, with its markers turned into emphasis.
 *
 * `snippet()` wraps the matched terms in the delimiters it was given rather
 * than returning positions, so this is the only way to know where the match
 * was without searching the excerpt again.
 */
private fun String.highlighted(): AnnotatedString =
    buildAnnotatedString {
        var rest = this@highlighted
        while (true) {
            val open = rest.indexOf('[')
            val close = rest.indexOf(']', startIndex = open + 1)
            if (open < 0 || close < 0) break
            append(rest.substring(0, open))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Markup.Strong)) {
                append(rest.substring(open + 1, close))
            }
            rest = rest.substring(close + 1)
        }
        append(rest)
    }

/**
 * Find inside the note that is open.
 *
 * Distinct from search, which answers "which note". 129 of this vault's notes
 * run past 20KB and the longest is 89KB, which is long enough that knowing a
 * word is in there is not the same as being able to reach it.
 *
 * The arrows step between hits rather than listing them: a list of forty
 * matches is another thing to read, and what is wanted is the next one.
 */
@Composable
private fun FindBar(
    query: String,
    matches: Int,
    matchBlocks: List<Int>,
    onQueryChange: (String) -> Unit,
    onJump: (Int) -> Unit,
) {
    var at by remember(matchBlocks) { mutableIntStateOf(0) }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.note_find)) },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Text(
                            text = if (matches == 0) "none" else "${at + 1}/$matches",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
            IconButton(
                onClick = {
                    if (matchBlocks.isEmpty()) return@IconButton
                    at = (at - 1 + matchBlocks.size) % matchBlocks.size
                    onJump(matchBlocks[at])
                },
                enabled = matches > 0,
            ) {
                LucideGlyph(
                    "chevron-up",
                    size = 20.dp,
                    contentDescription = stringResource(R.string.note_find_previous),
                )
            }
            IconButton(
                onClick = {
                    if (matchBlocks.isEmpty()) return@IconButton
                    at = (at + 1) % matchBlocks.size
                    onJump(matchBlocks[at])
                },
                enabled = matches > 0,
            ) {
                LucideGlyph("chevron-down", size = 20.dp, contentDescription = stringResource(R.string.note_find_next))
            }
        }
    }
}

/**
 * The open notes, as a row you can move along.
 *
 * Titles rather than numbers, truncated rather than wrapped: the strip has to
 * stay one line high or it is competing with the note for the screen. The one
 * being read is filled in; the rest are outlines.
 */
@Composable
private fun TabStrip(
    tabs: TabsState,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.tabs.forEachIndexed { index, tab ->
            val selected = index == tabs.active
            FilterChip(
                selected = selected,
                onClick = { onSelect(index) },
                label = {
                    Text(
                        text = tab.title.ifBlank { stringResource(R.string.note_kind) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = TAB_LABEL_WIDTH),
                    )
                },
                trailingIcon = {
                    // Only on the one being read. A close button on every tab
                    // in a row of six is a row of six things to hit by
                    // accident.
                    //
                    // A clickable box rather than an IconButton: the button
                    // insists on a 48dp minimum, which inside a chip is fought
                    // down to something that clips, and its click has to
                    // consume the event or the chip underneath also handles it
                    // and reopens what was just closed.
                    if (selected) {
                        Box(
                            Modifier
                                .size(TAB_CLOSE_SIZE)
                                .clickable { onClose(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            LucideGlyph(
                                "x",
                                size = 14.dp,
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    }
                },
            )
        }
    }
}

private val TAB_LABEL_WIDTH = 140.dp
private val TAB_CLOSE_SIZE = 26.dp

/**
 * What a link points at, and what to do with it.
 *
 * The title and where it lives answer "is this the one I meant"; the opening
 * words answer it when two notes share a name, which in this vault is 110
 * times over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkPeek(
    target: LinkTarget,
    onDismiss: () -> Unit,
    onOpenHere: () -> Unit,
    onOpenInNewTab: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AutoDirection(target.title) {
                Text(target.title, style = MaterialTheme.typography.titleMedium.inScript())
            }
            target.heading?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.link_at_heading, it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                target.path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (target.excerpt.isNotBlank()) {
                AutoDirection(target.excerpt) {
                    Text(
                        target.excerpt,
                        style = MaterialTheme.typography.bodySmall.inScript(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenHere) { Text(stringResource(R.string.link_open_here)) }
                OutlinedButton(onClick = onOpenInNewTab) { Text(stringResource(R.string.link_open_new_tab)) }
            }
        }
    }
}

/** A link that resolves to nothing, said plainly rather than ignored. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrokenLinkPeek(
    target: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.link_broken), style = MaterialTheme.typography.titleMedium)
            Text(
                target,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.link_broken_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
