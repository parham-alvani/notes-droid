package me.parham1995.notes.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import kotlinx.serialization.Serializable
import me.parham1995.notes.R
import me.parham1995.notes.data.StartScreen
import me.parham1995.notes.feature.browser.BrowserScreen
import me.parham1995.notes.feature.graph.GraphScreen
import me.parham1995.notes.feature.note.NoteScreen
import me.parham1995.notes.feature.search.SearchScreen
import me.parham1995.notes.feature.ssh.SshScreen
import me.parham1995.notes.feature.sync.SettingsHomeScreen
import me.parham1995.notes.feature.sync.SettingsSection
import me.parham1995.notes.feature.sync.SyncScreen
import me.parham1995.notes.feature.tags.TagsScreen
import me.parham1995.notes.feature.tags.TagsViewModel
import me.parham1995.notes.feature.tasks.TasksScreen
import me.parham1995.notes.ui.icon.LucideGlyph

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
object SearchRoute

@Serializable
object SettingsRoute

@Serializable
object SshRoute

/** One group of settings. Carried by name, which is stable across reordering. */
@Serializable
data class SettingsSectionRoute(
    val section: String,
)

/**
 * The tags of a vault, or the notes under one tag when [tag] names it.
 *
 * [vaultId] is the vault a tag was tapped in, so a note from one vault never
 * lists another's; [TagsViewModel.ACTIVE_VAULT] means the one being browsed.
 */
@Serializable
data class TagsRoute(
    val tag: String = "",
    val vaultId: Long = TagsViewModel.ACTIVE_VAULT,
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
) = navigate(NoteRoute(id, newTab, fresh)) {
    popUpTo<NoteRoute> { inclusive = true }
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

    // A one-shot: consumed so that rotating the phone afterwards does not yank
    // the person back to wherever they were sent half an hour ago.
    val requested by openScreen.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        val destination =
            when (requested) {
                "tasks" -> TasksRoute
                "search" -> SearchRoute
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
            Tab(SearchRoute, R.string.nav_search, "search"),
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

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = destination?.hierarchy()?.any { it.hasRoute(tab.route::class) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                LucideGlyph(
                                    name = tab.icon,
                                    size = NAV_ICON,
                                    // Follows the bar's own selected/unselected
                                    // colours instead of picking its own.
                                    tint = LocalContentColor.current,
                                    contentDescription = stringResource(tab.label),
                                )
                            },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination =
                when (start) {
                    StartScreen.BROWSE -> BrowseRoute()
                    StartScreen.TASKS -> TasksRoute
                    StartScreen.SEARCH -> SearchRoute
                },
            modifier = Modifier.fillMaxSize().padding(if (showBar) padding else PaddingValues()),
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
                    onOpenTags = { navController.navigate(TagsRoute()) },
                )
            }
            composable<TagsRoute> { entry ->
                val route = entry.toRoute<TagsRoute>()
                TagsScreen(
                    tag = route.tag,
                    vaultId = route.vaultId,
                    onBack = { navController.popBackStack() },
                    onOpenNote = { navController.openNote(it, fresh = true) },
                )
            }
            composable<TasksRoute> {
                TasksScreen(onOpenNote = { navController.openNote(it, fresh = true) })
            }
            composable<SearchRoute> {
                SearchScreen(
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
                    onOpenGraph = { navController.navigate(GraphRoute(it)) },
                    onBack = { navController.popBackStack() },
                    onOpenNote = { navController.openNote(it) },
                    onOpenFolder = { navController.navigate(BrowseRoute(it)) },
                    onOpenTag = { vaultId, tag -> navController.navigate(TagsRoute(tag, vaultId)) },
                )
            }
        }
    }
}

private fun androidx.navigation.NavDestination.hierarchy(): Sequence<androidx.navigation.NavDestination> =
    generateSequence(this) { it.parent }

private val NAV_ICON = 24.dp
