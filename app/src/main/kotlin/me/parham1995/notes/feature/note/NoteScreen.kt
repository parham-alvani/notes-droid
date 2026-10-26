package me.parham1995.notes.feature.note

import android.content.ClipData
import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import me.parham1995.notes.R
import me.parham1995.notes.data.Pin
import me.parham1995.notes.data.runCatchingUnlessCancelled
import me.parham1995.notes.feature.define.DefineActivity
import me.parham1995.notes.feature.drawer.FileDrawerSheet
import me.parham1995.notes.feature.drawer.FileDrawerViewModel
import me.parham1995.notes.markdown.Base
import me.parham1995.notes.markdown.FootnoteEntry
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.markdown.ReadingTime
import me.parham1995.notes.markdown.SpokenText
import me.parham1995.notes.markdown.footnotes
import me.parham1995.notes.reminder.ReminderViewModel
import me.parham1995.notes.reminder.forReading
import me.parham1995.notes.ui.Loading
import me.parham1995.notes.ui.LocalFullScreen
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.RescheduleSheet
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.icon.VaultIcon
import me.parham1995.notes.ui.image.ImageViewer
import me.parham1995.notes.ui.imageKey
import me.parham1995.notes.ui.noteTitleKey
import me.parham1995.notes.ui.pdf.PdfViewer
import me.parham1995.notes.ui.readingPadding
import me.parham1995.notes.ui.render.Attachments
import me.parham1995.notes.ui.render.FootnoteSheet
import me.parham1995.notes.ui.render.InlineActions
import me.parham1995.notes.ui.render.MarkdownDocument
import me.parham1995.notes.ui.render.RenderActions
import me.parham1995.notes.ui.render.Transcluded
import me.parham1995.notes.ui.roomyForFullScreen
import me.parham1995.notes.ui.sharedBoundsIn
import me.parham1995.notes.ui.sharedElementIn
import me.parham1995.notes.ui.sheetProperties
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

    // A tap on the page takes the bars away and gives them back, on a window
    // roomy enough to read full screen in and while the setting allows it.
    val fullScreen = LocalFullScreen.current
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    val tapForFullScreen = LocalReading.current.tapForFullScreen && roomyForFullScreen(windowSize, density)
    val immersive = fullScreen.on && tapForFullScreen
    // Left as the screen goes, or as the window stops being roomy: a full
    // screen nobody can tap back out of is a trap.
    LaunchedEffect(tapForFullScreen) { if (!tapForFullScreen) fullScreen.on = false }
    DisposableEffect(fullScreen) { onDispose { fullScreen.on = false } }
    val view = LocalView.current
    DisposableEffect(immersive, window) {
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (immersive) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (immersive) bars?.show(WindowInsetsCompat.Type.systemBars()) }
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
    // Which hit the find bar is on. The screen's rather than the bar's, so the
    // page can draw that one apart from the rest.
    var findAt by remember(noteId) { mutableIntStateOf(0) }
    var peeking by remember { mutableStateOf<LinkTarget?>(null) }
    var peekBroken by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf<Pair<LinkTarget, Transcluded>?>(null) }
    // A `[[x.base]]` link that was followed: the base and the view it named.
    var baseSheet by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val haptics = LocalHapticFeedback.current
    // A heading to land on once the note it belongs to has loaded.
    var pendingHeading by remember { mutableStateOf<String?>(null) }
    // A heading asked for with the note it is in, handed over only once that
    // note is the one on screen: set while another is still showing, it would
    // be looked for there, not found, and dropped.
    var headingIn by remember(noteId, heading) { mutableStateOf(heading?.let { noteId to it }) }

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()

    // The open notes as pages, so a swipe moves between them the way it does
    // in a browser. The strip and the pager say the same thing: a tap on the
    // strip, a link opened beside this one, a tab closed, and the page
    // follows; a swipe that settles, and the tab follows.
    val pager = rememberPagerState(initialPage = tabs.active) { tabs.tabs.size.coerceAtLeast(1) }
    LaunchedEffect(tabs.active, tabs.tabs.size) {
        if (pager.currentPage != tabs.active && tabs.active < pager.pageCount) {
            pager.animateScrollToPage(tabs.active)
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page -> if (page != tabs.active) viewModel.selectTab(page) }
    }

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
    // Whether this note has had its first landing: where it was left is for
    // arriving, not for every heading asked for once it is being read.
    var landed by remember(loadedId) { mutableStateOf(false) }
    LaunchedEffect(loadedId, pendingHeading) {
        val note = state.note ?: return@LaunchedEffect
        if (note.id != loadedId) return@LaunchedEffect
        val go = landing(note.headings, pendingHeading, note.blockRefs, note.scrollIndex, firstLanding = !landed)
        if (go is Landing.To) listState.scrollToItem(viewModel.reveal(go.block))
        pendingHeading = null
        landed = true
    }
    // Where the note is left, each time a scroll comes to rest on another
    // block -- not once as the screen goes, which a followed link, a tab
    // switch or the app being swept away never reached. Only after the first
    // landing, so the place the last note was left is not written to this one.
    LaunchedEffect(loadedId, landed) {
        val id = loadedId ?: return@LaunchedEffect
        if (!landed) return@LaunchedEffect
        snapshotFlow { if (listState.isScrollInProgress) null else listState.firstVisibleItemIndex }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { viewModel.rememberScroll(id, it) }
    }
    // A new note starts with its title showing, however far down the last one
    // had pushed the bar.
    LaunchedEffect(loadedId) { topBar.state.heightOffset = 0f }

    // The block at the top of the screen, by its position in the note.
    fun topBlock(): Int = state.shownBlocks.getOrElse(listState.firstVisibleItemIndex) { 0 }

    // A find hit brought on screen -- unless it already is, so stepping through
    // three hits in one paragraph does not jerk the page under them.
    suspend fun bringToFind(block: Int) {
        val position = viewModel.reveal(block)
        val info = listState.layoutInfo
        val top = info.visibleItemsInfo.firstOrNull { it.index == position }?.offset
        if (findNeedsScroll(top, info.viewportStartOffset, info.viewportEndOffset)) {
            listState.animateScrollToItem(position)
        }
    }

    // A new word, or the note's text changed under it: start from the first
    // hit at or below the top of the screen, the way the Mac reader does.
    LaunchedEffect(state.matches) {
        val blocks = state.matches.map { it.blockIndex }
        findAt = findStart(blocks, topBlock())
        blocks.getOrNull(findAt)?.let { bringToFind(it) }
    }

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
                                    // A base opens as its tables.
                                    id == null && Base.isBase(target) -> baseSheet = target to heading
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
                            // A hold on a link reads it in place; a tap
                            // still asks where to open it.
                            onPreviewLink = { target, heading ->
                                val id = if (target.isBlank()) note.id else viewModel.targetOf(target)
                                if (id == null) {
                                    peekBroken = target
                                } else {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch {
                                        val peek = viewModel.peek(id) ?: return@launch
                                        val content =
                                            viewModel.preview(id, heading)
                                                ?: viewModel.preview(id, null)
                                                ?: return@launch
                                        previewing = peek.copy(heading = heading) to content
                                    }
                                }
                            },
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
                    queryTasks = { source -> viewModel.tasksQuery(note.vaultId, note.path, source) },
                    runBase = { block -> viewModel.runBase(note.vaultId, note.path, block) },
                    onCompleteQueryTask =
                        if (state.writable) {
                            { row -> viewModel.completeQueryTask(row) }
                        } else {
                            null
                        },
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
                // Hidden whole in full screen; otherwise it still slides away
                // with the scroll, as it always has.
                AnimatedVisibility(visible = !immersive, enter = expandVertically(), exit = shrinkVertically()) {
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
                                syncing = state.syncing,
                                onSync = viewModel::sync,
                                onSortTasks =
                                    if (state.writable && state.note?.isFolderNote == false) {
                                        { viewModel.sortTasks() }
                                    } else {
                                        null
                                    },
                                onSharePdf = {
                                    state.note?.let { note ->
                                        scope.launch {
                                            runCatchingUnlessCancelled {
                                                NotePdf.share(
                                                    context,
                                                    note.title,
                                                    note.blocks,
                                                )
                                            }.onFailure {
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
                }
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
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
                    // By note, so a page keeps its state while tabs open and
                    // close around it, and a page that is new is new.
                    key = { page -> tabs.tabs.getOrNull(page)?.noteId ?: (-1L - page) },
                    beyondViewportPageCount = 0,
                    userScrollEnabled = tabs.tabs.size > 1,
                ) { page ->
                    // Only the page being read is the note; the one sliding
                    // in beside it is its title and opening line until the
                    // swipe settles and it becomes the one being read. One
                    // document at a time is what keeps a swipe between two
                    // long notes from composing both.
                    if (page != tabs.active && tabs.tabs.size > 1) {
                        TabPreview(tabs.tabs[page], peek = viewModel::peek)
                    } else {
                        Column(Modifier.fillMaxSize()) {
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
                                    at = findAt,
                                    onQueryChange = viewModel::find,
                                    onStep = { direction ->
                                        findAt = stepFind(findAt, state.matches.size, direction)
                                        state.matches.getOrNull(findAt)?.let { hit ->
                                            scope.launch { bringToFind(hit.blockIndex) }
                                        }
                                    },
                                )
                            }

                            if (showContents) {
                                FolderContents(state.contents, onOpenNote, onOpenFolder)
                            } else {
                                // The column held to a reading width on a wide window.
                                BoxWithConstraints(Modifier.fillMaxSize()) {
                                    val readingWidth = LocalReading.current.lineWidth
                                    when {
                                        // The same page the swipe showed, rather than a
                                        // spinner in its place for the moment the note
                                        // takes to parse.
                                        state.loading ->
                                            tabs.current?.let { TabPreview(it, peek = viewModel::peek) }
                                                ?: Loading(Modifier.align(Alignment.Center))
                                        state.missing ->
                                            Text(
                                                stringResource(R.string.note_not_on_device),
                                                Modifier.align(Alignment.Center).padding(24.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )

                                        else ->
                                            state.note?.let { note ->
                                                val margins = readingPadding(maxWidth, readingWidth)
                                                // Pulled down from the top of the note, the
                                                // way the file list is; the note re-reads
                                                // itself when the sync is over.
                                                PullToRefreshBox(
                                                    isRefreshing = state.syncing,
                                                    onRefresh = viewModel::sync,
                                                    modifier = Modifier.fillMaxSize(),
                                                ) {
                                                    MarkdownDocument(
                                                        blocks = note.blocks,
                                                        brokenLinks = state.brokenLinks,
                                                        listState = listState,
                                                        contentPadding = margins,
                                                        onPinch = viewModel::pinchTextScale,
                                                        actions = noteActions ?: RenderActions(),
                                                        spoken = spokenBlock,
                                                        folded = state.folded,
                                                        onToggleFold = viewModel::toggleFold,
                                                        onTapPage =
                                                            if (tapForFullScreen) {
                                                                { fullScreen.on = !fullScreen.on }
                                                            } else {
                                                                null
                                                            },
                                                        find =
                                                            if (finding) {
                                                                findTarget(
                                                                    state.findQuery,
                                                                    state.matches.map { it.blockIndex },
                                                                    findAt,
                                                                )
                                                            } else {
                                                                null
                                                            },
                                                    )
                                                }
                                            }
                                    }
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
        // Predictive: the picture shrinks and fades with the back gesture, so
        // what letting go will do is visible before it is done. Cancelling
        // the gesture puts it back; finishing it hands over to the exit.
        var backProgress by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(zoomed) { if (zoomed != null) backProgress = 0f }
        PredictiveBackHandler(enabled = zoomed != null) { gesture ->
            try {
                gesture.collect { backProgress = it.progress }
                zoomed = null
            } catch (cancelled: CancellationException) {
                backProgress = 0f
                throw cancelled
            }
        }
        AnimatedVisibility(
            visible = zoomed != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier.graphicsLayer {
                    val scale = 1f - BACK_SHRINK * backProgress
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - BACK_FADE * backProgress
                },
        ) {
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

        baseSheet?.let { (target, view) ->
            state.note?.let { note ->
                BaseSheet(
                    title = target.substringAfterLast('/').removeSuffix(".base"),
                    view = view,
                    run = { viewModel.runBase(note.vaultId, note.path, MdBlock.BaseView(-1, target = target)) },
                    onDismiss = { baseSheet = null },
                    onOpen = { id ->
                        baseSheet = null
                        viewModel.openTab(id, inNewTab = false)
                    },
                )
            }
        }

        previewing?.let { (target, content) ->
            LinkPreview(
                target = target,
                content = content,
                vaultId = state.note?.vaultId ?: 0,
                onDismiss = { previewing = null },
                onOpenHere = {
                    previewing = null
                    if (target.noteId == state.note?.id) {
                        pendingHeading = target.heading
                    } else {
                        headingIn = target.heading?.let { target.noteId to it }
                        viewModel.openTab(target.noteId, inNewTab = false)
                    }
                },
                onOpenInNewTab = {
                    previewing = null
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
            val headings = state.note?.headings.orEmpty()
            OutlineSheet(
                headings = headings,
                // Read once, as the sheet opens: the page is behind it and
                // does not move while it is up.
                current = remember(headings) { currentHeading(headings, topBlock()) },
                onDismiss = { showOutline = false },
                onJump = { heading ->
                    showOutline = false
                    scope.launch { listState.animateScrollToItem(viewModel.reveal(heading.blockIndex)) }
                },
                reading = remember(state.note) { state.note?.blocks?.let(ReadingTime::of) },
            )
        }

        if (showBacklinks) {
            ModalBottomSheet(onDismissRequest = { showBacklinks = false }, properties = sheetProperties()) {
                // Looked up when the sheet opens rather than with the note: it is a
                // full-text search, and most notes are read without anyone asking.
                LaunchedEffect(noteId) { viewModel.loadMentions() }

                LazyColumn {
                    if (state.backlinks.isNotEmpty()) {
                        item { SheetLabel(stringResource(R.string.note_linked_from, state.backlinks.size)) }
                    }
                    items(state.backlinks, key = { "link-" + it.linkId }) { row ->
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
                        item { SheetLabel(stringResource(R.string.note_mentioned_in, state.mentions.size)) }
                    }
                    items(state.mentions, key = { "mention-" + it.noteId }) { hit ->
                        ReferenceRow(
                            title = hit.title,
                            context = hit.snippet.highlighted(Markup.Strong),
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

/** How far the image viewer shrinks and fades at the end of a back gesture. */
private const val BACK_SHRINK = 0.1f
private const val BACK_FADE = 0.3f

/** A picture shown full screen, by the vault it is in and its path there. */
private data class ZoomedImage(
    val vaultId: Long,
    val path: String,
    val alt: String?,
)
