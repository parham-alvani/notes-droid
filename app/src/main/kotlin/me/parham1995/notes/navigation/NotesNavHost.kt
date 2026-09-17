package me.parham1995.notes.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import me.parham1995.notes.feature.browser.BrowserScreen
import me.parham1995.notes.feature.note.NoteScreen
import me.parham1995.notes.feature.search.SearchScreen
import me.parham1995.notes.feature.sync.SyncScreen

/**
 * Routes are type-safe and a note is addressed by its **id**, never its path.
 * Paths in a real vault contain spaces, `#`, and non-Latin script, and run past
 * 200 characters; encoding them into route arguments is a bug factory an
 * integer sidesteps.
 */
@Serializable
object BrowseRoute

@Serializable
object SearchRoute

@Serializable
object SettingsRoute

@Serializable
data class NoteRoute(
    val id: Long,
)

private data class Tab(
    val route: Any,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun NotesNavHost() {
    val navController = rememberNavController()
    val tabs =
        listOf(
            Tab(BrowseRoute, "Browse", Icons.Filled.Home),
            Tab(SearchRoute, "Search", Icons.Filled.Search),
            Tab(SettingsRoute, "Settings", Icons.Filled.Settings),
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
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = BrowseRoute,
            modifier = Modifier.fillMaxSize().padding(if (showBar) padding else PaddingValues()),
        ) {
            composable<BrowseRoute> {
                BrowserScreen(onOpenNote = { navController.navigate(NoteRoute(it)) })
            }
            composable<SearchRoute> {
                SearchScreen(onOpenNote = { navController.navigate(NoteRoute(it)) })
            }
            composable<SettingsRoute> { SyncScreen() }
            composable<NoteRoute> { entry ->
                val route = entry.toRoute<NoteRoute>()
                NoteScreen(
                    noteId = route.id,
                    onBack = { navController.popBackStack() },
                    onOpenNote = { navController.navigate(NoteRoute(it)) },
                )
            }
        }
    }
}

private fun androidx.navigation.NavDestination.hierarchy(): Sequence<androidx.navigation.NavDestination> =
    generateSequence(this) { it.parent }
