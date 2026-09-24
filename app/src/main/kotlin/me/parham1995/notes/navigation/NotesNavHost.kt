package me.parham1995.notes.navigation

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.parham1995.notes.R
import me.parham1995.notes.data.Destination
import me.parham1995.notes.data.StartScreen
import me.parham1995.notes.feature.browser.BrowserScreen
import me.parham1995.notes.feature.graph.GraphScreen
import me.parham1995.notes.feature.note.NoteScreen
import me.parham1995.notes.feature.search.SearchScreen
import me.parham1995.notes.feature.ssh.SshScreen
import me.parham1995.notes.feature.sync.SettingsHomeScreen
import me.parham1995.notes.feature.sync.SettingsSection
import me.parham1995.notes.feature.sync.SyncScreen
import me.parham1995.notes.feature.tasks.TasksScreen

/**
 * Routes are type-safe and a note is addressed by its **id**, never its path.
 * Paths in a real vault contain spaces, `#`, and non-Latin script, and run past
 * 200 characters; encoding them into route arguments is a bug factory an
 * integer sidesteps.
 */
@Serializable
data class BrowseRoute(
    /**
     * Where in the tree to open. Empty is the vault root, which is what the
     * tab itself navigates to; a path is how a folder with no note of its own
     * is opened from somewhere else.
     */
    val path: String = "",
)

@Serializable
object TasksRoute

@Serializable
data class SearchRoute(
    /**
     * What to search for on arrival -- a bookmarked search, or one another app
     * asked for. Empty is the tab itself, which keeps whatever was typed last.
     */
    val query: String = "",
)

@Serializable
object SettingsRoute

@Serializable
object SshRoute

/** One group of settings. Carried by name, which is stable across reordering. */
@Serializable
data class SettingsSectionRoute(
    val section: String,
)

@Serializable
data class GraphRoute(
    val id: Long,
)

/**
 * Shows a note, in the single note destination there is.
 *
 * Notes are tabs now, so the back stack must not also keep a list of them --
 * two records of what is open disagree, and the one that wins is whichever
 * screen happens to still be composed. The symptom is a strip that says one
 * note is selected while the page below it shows another.
 *
 * Any note already on the stack is replaced, so there is exactly one note
 * screen and back leaves the reader rather than walking a second history.
 */
private fun NavController.openNote(
    id: Long,
    newTab: Boolean = false,
    fresh: Boolean = false,
    heading: String? = null,
) = navigate(NoteRoute(id, newTab, fresh, heading)) {
    popUpTo<NoteRoute> { inclusive = true }
    launchSingleTop = true
}

/** The browser's root, as its tab would open it: for a vault just switched to. */
private fun NavController.browseRoot() =
    navigate(BrowseRoute()) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }

/**
 * The search tab, asked to look for [query].
 *
 * Single-top, so a search already on screen takes the new query rather than
 * stacking a second search screen under the first.
 */
private fun NavController.search(query: String) =
    navigate(SearchRoute(query)) {
        launchSingleTop = true
    }

@Serializable
data class NoteRoute(
    val id: Long,
    /**
     * Beside what is already open, rather than in place of it.
     *
     * Arriving from the browser or a search is starting a new thread of
     * reading, so it replaces what was being read -- otherwise a session's
     * worth of browsing leaves a row of twenty tabs nobody asked for. Holding
     * a row asks for the other thing.
     */
    val newTab: Boolean = false,
    /**
     * Whether the reader is being entered from outside it.
     *
     * The browser, a search result, a task and a launcher shortcut all start a
     * new thread of reading, so the tab they land in begins again at this note
     * and back returns to the list the note was picked from. A link followed
     * inside the reader -- a wikilink, a backlink, an entry in a folder note --
     * is the next step of the thread already being read, and pushes.
     */
    val fresh: Boolean = false,
    /** A heading to land on, for a bookmark that names one. */
    val heading: String? = null,
)

private data class Tab(
    val route: Any,
    @param:StringRes val label: Int,
    /** A Lucide name, so the bar matches the glyphs used everywhere else. */
    val icon: String,
)

@Composable
fun NotesNavHost(
    openScreen: StateFlow<String?> = MutableStateFlow(null),
    openNote: StateFlow<Long?> = MutableStateFlow(null),
    openLink: StateFlow<String?> = MutableStateFlow(null),
    startScreen: StartScreen = StartScreen.BROWSE,
) {
    val navController = rememberNavController()

    // Read once. A NavHost given a different start destination builds a new
    // graph, which resets the back stack: changing the setting in Settings
    // threw the person out of the screen they changed it in. The new choice
    // applies from the next launch, which is what a start screen is.
    val start = rememberSaveable { startScreen }

    // Whether launch has already decided where to be: either the note that was
    // being read has been reopened, or something outside the app asked for a
    // screen or a note of its own. Declared first because the requests below
    // settle it too.
    var resumed by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val jumps: JumpViewModel = hiltViewModel()

    // Today's daily note, found and never made: the app does not write notes,
    // so a day without one says where it would be and leaves it at that.
    fun openToday() =
        scope.launch {
            when (val found = jumps.today()) {
                is Destination.Note -> navController.openNote(found.noteId, fresh = true)
                is Destination.NoDailyNote ->
                    snackbar.showSnackbar(resources.getString(R.string.today_missing, found.path))
                // A day is only ever a note or the lack of one.
                else -> Unit
            }
        }

    // A one-shot: consumed so that rotating the phone afterwards does not yank
    // the person back to wherever they were sent half an hour ago.
    val requested by openScreen.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        if (requested == SCREEN_TODAY) {
            resumed = true
            openToday()
            (openScreen as? MutableStateFlow)?.value = null
            return@LaunchedEffect
        }
        val destination =
            when (requested) {
                "tasks" -> TasksRoute
                "search" -> SearchRoute()
                else -> null
            }
        if (destination != null) {
            // Asked for by name, so reopening the last note on top of it would
            // bury exactly what was asked for.
            resumed = true
            navController.navigate(destination) { launchSingleTop = true }
            (openScreen as? MutableStateFlow)?.value = null
        }
    }

    val requestedNote by openNote.collectAsStateWithLifecycle()
    LaunchedEffect(requestedNote) {
        requestedNote?.let { id ->
            // A launcher shortcut or a widget: outside the reader, so it starts
            // its own thread rather than landing on the end of the last one.
            // The resume below steps aside: the tabs finish restoring after
            // this, and reopening the last note then replaced the one tapped.
            resumed = true
            navController.openNote(id, fresh = true)
            (openNote as? MutableStateFlow)?.value = null
        }
    }
    // An obsidian:// link from another app, followed in the vault it names.
    // Whatever cannot be followed still opens the app, and says why.
    val requestedLink by openLink.collectAsStateWithLifecycle()
    LaunchedEffect(requestedLink) {
        val uri = requestedLink ?: return@LaunchedEffect
        resumed = true
        (openLink as? MutableStateFlow)?.value = null
        val message =
            when (val found = jumps.follow(uri)) {
                is Destination.Note -> {
                    navController.openNote(found.noteId, fresh = true, heading = found.heading)
                    null
                }
                is Destination.Search -> {
                    navController.search(found.query)
                    null
                }
                is Destination.Vault -> {
                    navController.browseRoot()
                    null
                }
                is Destination.UnknownNote -> {
                    navController.browseRoot()
                    resources.getString(R.string.link_unknown_note, found.file)
                }
                is Destination.UnknownVault -> resources.getString(R.string.link_unknown_vault, found.name)
                is Destination.NoDailyNote, null -> resources.getString(R.string.link_not_followed)
            }
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // Reopen the note that was being read, once and only at launch.
    //
    // The tabs are restored from storage asynchronously, so this waits for
    // that rather than guessing, and a saved flag keeps a rotation from
    // yanking someone back to a note they have since left. With nothing open
    // the start screen setting decides, as before.
    val resume: ResumeViewModel = hiltViewModel()
    val restored by resume.restored.collectAsStateWithLifecycle()
    LaunchedEffect(restored) {
        if (!restored || resumed) return@LaunchedEffect
        resumed = true
        resume.activeNote()?.let { navController.openNote(it) }
    }

    val tabs =
        listOf(
            Tab(BrowseRoute(), R.string.nav_browse, "folder-tree"),
            Tab(TasksRoute, R.string.tasks_title, "list-todo"),
            Tab(SearchRoute(), R.string.nav_search, "search"),
            Tab(SettingsRoute, R.string.settings_title, "settings"),
        )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    // The note screen is pushed on top of a tab rather than being one, so the
    // bar hides there and back returns to where the link was followed. The
    // graph is reached only from a note and goes back to it, so it belongs on
    // the same side of that line -- with the bar it flashes in for one screen
    // and offers a way out of the reader that back already is.
    val showBar =
        destination?.hasRoute(NoteRoute::class) != true &&
            destination?.hasRoute(GraphRoute::class) != true

    TabFrame(
        tabs =
            tabs.map { tab ->
                TabEntry(
                    label = stringResource(tab.label),
                    icon = tab.icon,
                    selected = destination?.hierarchy()?.any { it.hasRoute(tab.route::class) } == true,
                    onClick = {
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            },
        showTabs = showBar,
        snackbar = snackbar,
    ) { placement ->
        NavHost(
            navController = navController,
            startDestination =
                when (start) {
                    StartScreen.BROWSE -> BrowseRoute()
                    StartScreen.TASKS -> TasksRoute
                    StartScreen.SEARCH -> SearchRoute()
                },
            modifier = placement,
        ) {
            composable<BrowseRoute> { entry ->
                BrowserScreen(
                    initialPath = entry.toRoute<BrowseRoute>().path,
                    onOpenNote = { navController.openNote(it, fresh = true) },
                    onOpenNoteInNewTab = { navController.openNote(it, newTab = true) },
                    onOpenAdvancedSettings = {
                        navController.navigate(
                            SettingsSectionRoute(SettingsSection.ADVANCED.name),
                        )
                    },
                    onOpenHeading = { id, heading -> navController.openNote(id, fresh = true, heading = heading) },
                    onSearch = { navController.search(it) },
                    onToday = { openToday() },
                )
            }
            composable<TasksRoute> {
                TasksScreen(onOpenNote = { navController.openNote(it, fresh = true) })
            }
            composable<SearchRoute> { entry ->
                SearchScreen(
                    initialQuery = entry.toRoute<SearchRoute>().query,
                    onOpenNote = { navController.openNote(it, fresh = true) },
                    onOpenNoteInNewTab = { navController.openNote(it, newTab = true) },
                )
            }
            composable<SettingsRoute> {
                SettingsHomeScreen(
                    onOpenSection = { navController.navigate(SettingsSectionRoute(it.name)) },
                )
            }
            composable<SettingsSectionRoute> { entry ->
                val section =
                    SettingsSection.entries
                        .firstOrNull { it.name == entry.toRoute<SettingsSectionRoute>().section }
                        ?: SettingsSection.REPOSITORIES
                SyncScreen(
                    section = section,
                    onBack = { navController.popBackStack() },
                    onManageSshKeys = { navController.navigate(SshRoute) },
                )
            }
            composable<GraphRoute> { entry ->
                GraphScreen(
                    noteId = entry.toRoute<GraphRoute>().id,
                    onBack = { navController.popBackStack() },
                    onOpenNote = { navController.openNote(it) },
                )
            }
            composable<SshRoute> {
                SshScreen(onBack = { navController.popBackStack() })
            }
            composable<NoteRoute> { entry ->
                val route = entry.toRoute<NoteRoute>()
                NoteScreen(
                    noteId = route.id,
                    openInNewTab = route.newTab,
                    fresh = route.fresh,
                    heading = route.heading,
                    onOpenGraph = { navController.navigate(GraphRoute(it)) },
                    onBack = { navController.popBackStack() },
                    onOpenNote = { navController.openNote(it) },
                    onOpenFolder = { navController.navigate(BrowseRoute(it)) },
                    onSearch = { navController.search(it) },
                )
            }
        }
    }
}

private fun androidx.navigation.NavDestination.hierarchy(): Sequence<androidx.navigation.NavDestination> =
    generateSequence(this) { it.parent }
