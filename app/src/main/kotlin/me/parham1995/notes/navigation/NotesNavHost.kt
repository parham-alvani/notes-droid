package me.parham1995.notes.navigation

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import me.parham1995.notes.data.StartScreen
import me.parham1995.notes.feature.browser.BrowserScreen
import me.parham1995.notes.feature.note.NoteScreen
import me.parham1995.notes.feature.search.SearchScreen
import me.parham1995.notes.feature.ssh.SshScreen
import me.parham1995.notes.feature.sync.SettingsHomeScreen
import me.parham1995.notes.feature.sync.SettingsSection
import me.parham1995.notes.feature.sync.SyncScreen
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

@Serializable
data class NoteRoute(
    val id: Long,
)

private data class Tab(
    val route: Any,
    val label: String,
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
            navController.navigate(destination) { launchSingleTop = true }
            (openScreen as? MutableStateFlow)?.value = null
        }
    }

    val requestedNote by openNote.collectAsStateWithLifecycle()
    LaunchedEffect(requestedNote) {
        requestedNote?.let { id ->
            navController.navigate(NoteRoute(id)) { launchSingleTop = true }
            (openNote as? MutableStateFlow)?.value = null
        }
    }
    val tabs =
        listOf(
            Tab(BrowseRoute(), "Browse", "folder-tree"),
            Tab(TasksRoute, "Tasks", "list-todo"),
            Tab(SearchRoute, "Search", "search"),
            Tab(SettingsRoute, "Settings", "settings"),
        )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    // The note screen is pushed on top of a tab rather than being one, so the
    // bar hides there and back returns to where the link was followed.
    val showBar = destination?.hasRoute(NoteRoute::class) != true

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
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination =
                when (startScreen) {
                    StartScreen.BROWSE -> BrowseRoute()
                    StartScreen.TASKS -> TasksRoute
                    StartScreen.SEARCH -> SearchRoute
                },
            modifier = Modifier.fillMaxSize().padding(if (showBar) padding else PaddingValues()),
        ) {
            composable<BrowseRoute> { entry ->
                BrowserScreen(
                    initialPath = entry.toRoute<BrowseRoute>().path,
                    onOpenNote = { navController.navigate(NoteRoute(it)) },
                )
            }
            composable<TasksRoute> {
                TasksScreen(onOpenNote = { navController.navigate(NoteRoute(it)) })
            }
            composable<SearchRoute> {
                SearchScreen(onOpenNote = { navController.navigate(NoteRoute(it)) })
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
            composable<SshRoute> {
                SshScreen(onBack = { navController.popBackStack() })
            }
            composable<NoteRoute> { entry ->
                val route = entry.toRoute<NoteRoute>()
                NoteScreen(
                    noteId = route.id,
                    onBack = { navController.popBackStack() },
                    onOpenNote = { navController.navigate(NoteRoute(it)) },
                    onOpenFolder = { navController.navigate(BrowseRoute(it)) },
                )
            }
        }
    }
}

private fun androidx.navigation.NavDestination.hierarchy(): Sequence<androidx.navigation.NavDestination> =
    generateSequence(this) { it.parent }

private val NAV_ICON = 24.dp
