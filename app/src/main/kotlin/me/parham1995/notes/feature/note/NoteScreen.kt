package me.parham1995.notes.feature.note

import android.content.ClipData
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.runCatchingUnlessCancelled
import me.parham1995.notes.feature.define.DefineActivity
import me.parham1995.notes.feature.drawer.FileDrawerSheet
import me.parham1995.notes.feature.drawer.FileDrawerViewModel
import me.parham1995.notes.markdown.FootnoteEntry
import me.parham1995.notes.markdown.SpokenText
import me.parham1995.notes.markdown.footnotes
import me.parham1995.notes.reminder.ReminderViewModel
import me.parham1995.notes.reminder.forReading
import me.parham1995.notes.ui.AutoDirection
import me.parham1995.notes.ui.ItemRow
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.RescheduleSheet
import me.parham1995.notes.ui.VaultRowItem
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.icon.VaultIcon
import me.parham1995.notes.ui.image.ImageViewer
import me.parham1995.notes.ui.imageKey
import me.parham1995.notes.ui.inScript
import me.parham1995.notes.ui.noteTitleKey
import me.parham1995.notes.ui.pdf.PdfViewer
import me.parham1995.notes.ui.readingPadding
import me.parham1995.notes.ui.render.Attachments
import me.parham1995.notes.ui.render.FootnoteSheet
import me.parham1995.notes.ui.render.InlineActions
import me.parham1995.notes.ui.render.MarkdownDocument
import me.parham1995.notes.ui.render.RenderActions
import me.parham1995.notes.ui.sharedBoundsIn
import me.parham1995.notes.ui.sharedElementIn
import me.parham1995.notes.ui.text
import me.parham1995.notes.ui.theme.Markup
import me.parham1995.notes.widget.NoteWidget
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    noteId: Long,
    openInNewTab: Boolean,
    fresh: Boolean = false,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenGraph: (Long) -> Unit = {},
    /** A heading to land on, for a note opened from a bookmark that names one. */
    heading: String? = null,
    onOpenTag: (vaultId: Long, tag: String) -> Unit = { _, _ -> },
    viewModel: NoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nothingOpens = stringResource(R.string.error_nothing_opens)
    val noteMissing = stringResource(R.string.note_missing)
    val couldNotFetch = stringResource(R.string.error_could_not_fetch)
    val linkNotFound = stringResource(R.string.link_not_found)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // The bar slides away as the note is read down and comes back on the
    // first scroll up. On a phone it is a seventh of the screen, and nothing
    // on it is needed mid-paragraph.
    val topBar = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Awake while a note is open, when asked for. The flag is the window's,
    // so it is taken off again as this screen goes rather than left for the
    // settings page that follows it.
    val keepScreenOn = LocalReading.current.keepScreenOn
    val window = LocalActivity.current?.window
    DisposableEffect(keepScreenOn, window) {
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (keepScreenOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var showOutline by remember { mutableStateOf(false) }
    var showBacklinks by remember { mutableStateOf(false) }
    // Reset per note, so following a link from a folder note's contents does
    // not land on the next note with the wrong tab selected.
    var showContents by remember(noteId) { mutableStateOf(false) }
    // A PDF opens in place; everything else is handed to another app.
    var reading by remember(noteId) { mutableStateOf<File?>(null) }
    // The embedded image being looked at full screen, by its vault path.
    // With its vault: one picked from the drawer is in the vault the drawer
    // shows, which need not be the note's.
    var zoomed by remember(noteId) { mutableStateOf<ZoomedImage?>(null) }
    var finding by remember(noteId) { mutableStateOf(false) }
    var peeking by remember { mutableStateOf<LinkTarget?>(null) }
    var peekBroken by remember { mutableStateOf<String?>(null) }
    // A heading to land on once the note it belongs to has loaded.
    var pendingHeading by remember { mutableStateOf<String?>(null) }
    // A heading asked for with the note it is in, handed over only once that
    // note is the one on screen: set while another is still showing, it would
    // be looked for there, not found, and dropped.
    var headingIn by remember(noteId, heading) { mutableStateOf(heading?.let { noteId to it }) }

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()

    // Read aloud: one engine per note screen, shut down when it goes, so
    // leaving the note stops the voice rather than leaving it talking.
    // The block being read, so the page follows along; null when silent.
    var spokenBlock by remember { mutableStateOf<Int?>(null) }
    val speaking = spokenBlock != null
    val noPersianVoice = stringResource(R.string.read_aloud_no_persian)
    val cannotPlaceWidget = stringResource(R.string.note_add_widget_unsupported)
    val pdfFailed = stringResource(R.string.note_share_pdf_failed)
    val reader =
        remember {
            ReadAloud(
                context,
                title = { state.note?.title.orEmpty() },
                onBlock = { spokenBlock = it },
                onNoPersian = { Toast.makeText(context, noPersianVoice, Toast.LENGTH_LONG).show() },
            )
        }
    DisposableEffect(reader) { onDispose { reader.shutdown() } }
    // Another note, another text: the old one is not read over the new.
    LaunchedEffect(state.note?.id) { if (speaking) reader.stop() }
    // Keep the paragraph being read on screen -- unless it already is, so the
    // page does not jump under someone reading along.
    LaunchedEffect(spokenBlock) {
        val block = spokenBlock ?: return@LaunchedEffect
        // A position on screen, not in the note: a fold above it moves it up.
        val position = viewModel.reveal(block)
        val shown = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (position !in shown.dropLast(1)) listState.animateScrollToItem(position)
    }

    // Pinned to the home screen by vault and path, so the pin outlives the id.
    val pins: PinViewModel = hiltViewModel()
    val pinned by pins.pinned.collectAsStateWithLifecycle()
    LaunchedEffect(state.note?.vaultId, state.note?.path) {
        state.note?.let { pins.track(it.vaultId, it.path) }
    }

    // Somewhere else to go without leaving this note. The reader had one way to
    // reach another file -- back out to the browser -- which loses the note on
    // screen and the place in it, and following a thought across four notes and
    // back is what this vault is actually read for.
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val files: FileDrawerViewModel = hiltViewModel()
    val drawerState by files.state.collectAsStateWithLifecycle()

    // An embedded file, or a Markdown link to one. A PDF is read here;
    // everything else belongs to whatever app owns that type.
    fun openAttachment(
        path: String,
        fetch: suspend (String) -> File? = viewModel::attachment,
        vaultId: Long? = state.note?.vaultId,
    ) {
        // A picture is shown here, zoomable, rather than handed to a chooser.
        if (Attachments.isImage(path) && vaultId != null) {
            zoomed = ZoomedImage(vaultId, path, alt = null)
            return
        }
        scope.launch {
            val file = fetch(path)
            val message =
                when {
                    file == null -> couldNotFetch.format(path.substringAfterLast('/'))
                    Attachments.isPdf(path) -> {
                        reading = file
                        null
                    }
                    Attachments.open(context, file) -> null
                    else -> nothingOpens
                }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    fun closeThen(action: () -> Unit) {
        scope.launch { drawer.close() }
        action()
    }

    // The route argument only ever seeds the set. After that the screen
    // follows whichever tab is being read, so switching tabs does not have to
    // navigate and lose the back stack.
    //
    // Until that seed has landed the route is the answer, not the tab strip:
    // the strip still has the tab that was open before, and reading it first
    // loaded that note on the way to this one.
    var seeded by remember(noteId) { mutableStateOf(false) }
    LaunchedEffect(noteId) {
        viewModel.openTab(noteId, inNewTab = openInNewTab, fresh = fresh)
        seeded = true
    }
    val activeId = if (seeded) tabs.current?.noteId ?: noteId else noteId

    LaunchedEffect(activeId) { viewModel.load(activeId) }

    // Back retraces this tab, not the app.
    //
    // Each tab keeps its own trail, so back goes to the note this one came
    // from rather than to whatever was opened last anywhere. With nothing
    // behind it in this tab the gesture falls through and leaves the reader,
    // which is the only way out; the button says so by being disabled.
    // Not while the drawer is over the page. The drawer registers a handler of
    // its own, but this one is composed inside the drawer's content slot and
    // would win -- navigating the note underneath instead of shutting the
    // drawer covering it.
    BackHandler(enabled = !drawer.isOpen && tabs.current?.canGoBack == true) { viewModel.back() }
    LaunchedEffect(state.note?.id, state.note?.title) {
        val note = state.note
        if (note != null) viewModel.retitleTab(note.id, note.title)
    }

    // Resume where this note was left. Keyed on the note's own id rather than
    // the argument, so it runs once the note has actually loaded.
    val loadedId = state.note?.id
    // Re-located on every opening, not once: the note underneath changes, and a
    // drawer still showing where you were three notes ago is one you stop
    // opening.
    LaunchedEffect(drawer.isOpen, loadedId) {
        if (drawer.isOpen) loadedId?.let { files.locate(it) }
    }
    LaunchedEffect(loadedId, headingIn) {
        val (id, text) = headingIn ?: return@LaunchedEffect
        if (loadedId == id) {
            pendingHeading = text
            headingIn = null
        }
    }
    LaunchedEffect(loadedId, pendingHeading) {
        val note = state.note ?: return@LaunchedEffect
        // A heading asked for wins over where the note was left: it is the
        // reason the note was opened at all.
        val target = blockForHeading(note.headings, pendingHeading, note.blockRefs)
        when {
            target != null -> {
                listState.scrollToItem(viewModel.reveal(target))
                pendingHeading = null
            }
            pendingHeading != null -> pendingHeading = null // renamed since; stop asking
            note.scrollIndex > 0 -> listState.scrollToItem(viewModel.reveal(note.scrollIndex))
        }
    }
    DisposableEffect(loadedId) {
        onDispose {
            if (loadedId != null) viewModel.rememberScroll(listState.firstVisibleItemIndex)
        }
    }
    // A new note starts with its title showing, however far down the last one
    // had pushed the bar.
    LaunchedEffect(loadedId) { topBar.state.heightOffset = 0f }

    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.text()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    // A task being moved to another day, or given a reminder, by its source line.
    val reminders: ReminderViewModel = hiltViewModel()
    val reminderScheduled = stringResource(R.string.reminder_scheduled)
    val taskNotIndexed = stringResource(R.string.task_not_indexed)
    var moving by remember(noteId) { mutableStateOf<Int?>(null) }
    moving?.let { line ->
        RescheduleSheet(
            today = java.time.LocalDate.now(),
            onChoose = { date ->
                moving = null
                viewModel.rescheduleTask(line, date)
            },
            onDismiss = { moving = null },
            canMove = state.writable,
            onRemind = { at ->
                moving = null
                state.note?.let { note ->
                    reminders.remindInNote(note.id, line, at) { set ->
                        val said = if (set) reminderScheduled.format(at.forReading()) else taskNotIndexed
                        Toast.makeText(context, said, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }

    var showPlaces by remember(noteId) { mutableStateOf(false) }
    if (showPlaces) {
        state.note?.let { note -> PlacesSheet(note.blocks, onDismiss = { showPlaces = false }) }
    }

    // A footnote opened from its number, over the note.
    var footnote by remember(noteId) { mutableStateOf<FootnoteEntry?>(null) }

    // Built here rather than inside the list, so the footnote sheet draws with
    // the same links the page has. Remembered, keyed on what it is built from:
    // a new set of actions on every recomposition was unequal to the last, so a
    // keystroke in the find bar or a snackbar recomposed every block on screen.
    val upcoming by reminders.upcoming.collectAsStateWithLifecycle()
    val remindedLines =
        state.note
            ?.let { note ->
                upcoming.filter { it.vaultId == note.vaultId && it.path == note.path }.map { it.line }.toSet()
            }.orEmpty()
    val noteActions =
        state.note?.let { note ->
            remember(note.id, note.vaultId, note.path, state.writable, remindedLines) {
                RenderActions(
                    vaultId = note.vaultId,
                    remindedLines = remindedLines,
                    // Ticking a box where it is
                    // written, rather than only
                    // from the task list.
                    onCompleteTask =
                        if (state.writable) {
                            { line -> viewModel.completeTask(line) }
                        } else {
                            null
                        },
                    // Always: a reminder needs no write, so the sheet opens
                    // on any vault and offers moving only where it can.
                    onRescheduleTask = { line -> moving = line },
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
                            onNoteLink = { id, heading ->
                                if (id == state.note?.id) {
                                    pendingHeading = heading
                                } else {
                                    scope.launch {
                                        peeking =
                                            viewModel
                                                .peek(
                                                    id,
                                                )?.copy(heading = heading)
                                    }
                                }
                            },
                            onBrokenLink = { target -> peekBroken = target },
                            onDefine = { word -> context.startActivity(DefineActivity.lookUp(context, word)) },
                            // The note's own vault: a tag means
                            // nothing across two of them.
                            onTag = { tag -> onOpenTag(note.vaultId, tag) },
                            // Found in the note on screen; an embedded
                            // note looks in its own.
                            onFootnote = { label ->
                                note.blocks
                                    .footnotes()
                                    .firstOrNull { it.label == label }
                                    ?.let { footnote = it }
                            },
                            onInternalLink = { destination ->
                                val route =
                                    routeOf(destination, note.path) {
                                        viewModel.targetOf(it)
                                    }
                                when (route) {
                                    is LinkRoute.Here ->
                                        pendingHeading =
                                            route.heading
                                    is LinkRoute.Note ->
                                        if (route.noteId == note.id) {
                                            pendingHeading = route.heading
                                        } else {
                                            scope.launch {
                                                peeking =
                                                    viewModel
                                                        .peek(route.noteId)
                                                        ?.copy(
                                                            heading = route.heading,
                                                        )
                                            }
                                        }
                                    is LinkRoute.File -> openAttachment(route.path)
                                    is LinkRoute.Nowhere ->
                                        Toast
                                            .makeText(
                                                context,
                                                linkNotFound.format(route.target),
                                                Toast.LENGTH_SHORT,
                                            ).show()
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
                    onImage = { path, alt -> state.note?.let { zoomed = ZoomedImage(it.vaultId, path, alt) } },
                    // Another note drawn in place, rather
                    // than offered to an app that had
                    // nothing to open.
                    transclude = viewModel::transclusion,
                    onAttachment = { path -> openAttachment(path) },
                    showFootnote = { footnote = it },
                )
            }
        }

    ModalNavigationDrawer(
        drawerState = drawer,
        // Swipe opens nothing: an edge swipe is the system back gesture, and a
        // drawer that fights it costs more than the button it saves. Once open,
        // a swipe shuts it, which is the half worth having.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            FileDrawerSheet(
                state = drawerState,
                viewModel = files,
                // Straight into the tab set rather than through the
                // navigator: the screen already follows whichever tab is
                // being read, so navigating would rebuild this destination
                // to show the same thing.
                onOpenNote = { id -> closeThen { viewModel.openTab(id, inNewTab = false) } },
                onOpenNoteInNewTab = { id -> closeThen { viewModel.openTab(id, inNewTab = true) } },
                onBrowseFolder = { path -> closeThen { onOpenFolder(path) } },
                onOpenFile = { path ->
                    closeThen { openAttachment(path, files::attachment, drawerState.activeVaultId.takeIf { it != 0L }) }
                },
                onOpenHeading = { id, text ->
                    closeThen {
                        headingIn = id to text
                        viewModel.openTab(id, inNewTab = false)
                    }
                },
                onSearch = { query -> closeThen { onSearch(query) } },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(topBar.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    scrollBehavior = topBar,
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
                                // Keyed on the note the route asked for, which
                                // is the one the row that opened it named --
                                // the loaded note arrives after the transition
                                // has already begun.
                                modifier = Modifier.sharedBoundsIn(noteTitleKey(noteId)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        // A Row, because the navigation slot lays its content
                        // out as a box: two buttons put in it directly are
                        // drawn one on top of the other, which is how the back
                        // arrow spent a build hidden underneath the drawer's.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // The files drawer, then back. Ordered that way
                            // because the leading slot is where a drawer is
                            // looked for, and back has a second home in the
                            // gesture.
                            IconButton(onClick = { scope.launch { drawer.open() } }) {
                                LucideGlyph(
                                    "panel-left",
                                    size = 20.dp,
                                    contentDescription = stringResource(R.string.drawer_title),
                                )
                            }
                            // Gone, rather than greyed out, when this tab has
                            // nowhere to go back to. A note is usually opened
                            // from a list and has no trail behind it yet, so a
                            // disabled arrow is what most of them wear most of
                            // the time -- permanent furniture that says only
                            // that a thing does not work, and takes the space
                            // the title needs on a phone. It appears once
                            // following a link has given it somewhere to go.
                            if (tabs.current?.canGoBack == true) {
                                IconButton(onClick = { viewModel.back() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back),
                                    )
                                }
                            }
                        }
                    },
                    actions = {
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
                                contentDescription =
                                    stringResource(
                                        if (finding) R.string.action_close else R.string.note_find,
                                    ),
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
                            Icon(
                                Icons.AutoMirrored.Filled.List,
                                contentDescription = stringResource(R.string.note_outline),
                            )
                        }
                        // The count is more use than an icon here: it says
                        // whether opening the sheet is worth it.
                        if (state.backlinks.isNotEmpty()) {
                            TextButton(onClick = { showBacklinks = true }) {
                                Text(
                                    pluralStringResource(
                                        R.plurals.note_links,
                                        state.backlinks.size,
                                        state.backlinks.size,
                                    ),
                                )
                            }
                        }
                        // Shares the note as it is written, not as it is rendered:
                        // what goes out is markdown, which is what the person on
                        // the other end can do something with.
                        NoteMenu(
                            enabled = state.note != null,
                            pinned = pinned,
                            onShare = {
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
                            onTogglePin = { pins.toggle() },
                            speaking = speaking,
                            onReadAloud = {
                                val note = state.note
                                when {
                                    speaking -> reader.stop()
                                    note != null -> reader.speak(SpokenText.of(note.blocks))
                                }
                            },
                            onPlaces = { showPlaces = true },
                            onSharePdf = {
                                state.note?.let { note ->
                                    scope.launch {
                                        runCatchingUnlessCancelled { NotePdf.share(context, note.title, note.blocks) }
                                            .onFailure {
                                                Toast
                                                    .makeText(
                                                        context,
                                                        pdfFailed,
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                            }
                                    }
                                }
                            },
                            onAddWidget = {
                                state.note?.let { note ->
                                    if (!NoteWidget.request(context, Pin(note.vaultId, note.path))) {
                                        Toast.makeText(context, cannotPlaceWidget, Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                        )
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
                // How far down the note this is, as a hairline under the bar.
                // Blank for a page that fits, and for the folder listing.
                ReadingProgress(listState, enabled = !showContents && state.note != null)
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
                // Stepping along a journal lands in this tab, so back steps
                // back along it too.
                state.periodic?.let { periodic ->
                    PeriodBar(periodic, onOpen = { id -> viewModel.openTab(id, inNewTab = false) })
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
                            onJump = { index ->
                                scope.launch { listState.animateScrollToItem(viewModel.reveal(index)) }
                            },
                            matchBlocks = state.matches.map { it.blockIndex },
                        )
                    }

                    if (showContents) {
                        FolderContents(state.contents, onOpenNote, onOpenFolder)
                    } else {
                        // The column held to a reading width on a wide window.
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val readingWidth = LocalReading.current.lineWidth
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
                                            contentPadding = readingPadding(maxWidth, readingWidth),
                                            onPinch = viewModel::pinchTextScale,
                                            actions = noteActions ?: RenderActions(),
                                            spoken = spokenBlock,
                                            folded = state.folded,
                                            onToggleFold = viewModel::toggleFold,
                                        )
                                    }
                            }
                        }
                    }
                }
            }
        }

        // The image names its own vault: the note's for an embed, the
        // drawer's for a file picked there.
        //
        // Drawn over the page in the same window rather than in a dialog, so
        // the picture can grow out of its place in the note and shrink back
        // into it; a dialog is another window, and nothing is shared across
        // one. The last image is kept through the exit so there is something
        // to animate away.
        var shownImage by remember { mutableStateOf<ZoomedImage?>(null) }
        zoomed?.let { shownImage = it }
        BackHandler(enabled = zoomed != null) { zoomed = null }
        AnimatedVisibility(visible = zoomed != null, enter = fadeIn(), exit = fadeOut()) {
            shownImage?.let { (vaultId, path, alt) ->
                ImageViewer(
                    vaultId = vaultId,
                    path = path,
                    alt = alt,
                    onDismiss = { zoomed = null },
                    onOpenExternally = {
                        scope.launch {
                            val file = files.attachmentIn(vaultId, path)
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
                    modifier = Modifier.sharedElementIn(imageKey(vaultId, path), visibility = this),
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
                    headingIn = target.heading?.let { target.noteId to it }
                    viewModel.openTab(target.noteId, inNewTab = false)
                },
                onOpenInNewTab = {
                    peeking = null
                    headingIn = target.heading?.let { target.noteId to it }
                    viewModel.openTab(target.noteId, inNewTab = true)
                },
            )
        }

        peekBroken?.let { target ->
            BrokenLinkPeek(target = target, onDismiss = { peekBroken = null })
        }

        footnote?.let { entry ->
            // Following a link from inside it leaves the sheet behind.
            val closing =
                noteActions?.let { actions ->
                    actions.copy(
                        inline =
                            actions.inline.copy(
                                onWikiLink = { target, heading ->
                                    footnote = null
                                    actions.inline.onWikiLink(target, heading)
                                },
                                onInternalLink = { destination ->
                                    footnote = null
                                    actions.inline.onInternalLink(destination)
                                },
                                onTag = { tag ->
                                    footnote = null
                                    actions.inline.onTag(tag)
                                },
                            ),
                    )
                } ?: RenderActions()
            FootnoteSheet(
                entry = entry,
                actions = closing,
                brokenLinks = state.brokenLinks,
                onDismiss = { footnote = null },
            )
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
                                            scope.launch {
                                                listState.animateScrollToItem(viewModel.reveal(heading.blockIndex))
                                            }
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

/** A picture shown full screen, by the vault it is in and its path there. */
private data class ZoomedImage(
    val vaultId: Long,
    val path: String,
    val alt: String?,
)
