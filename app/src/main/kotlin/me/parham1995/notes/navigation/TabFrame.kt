package me.parham1995.notes.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.parham1995.notes.ui.icon.LucideGlyph

/** One of the app's tabs, as the bar or the rail draws it. */
internal class TabEntry(
    val label: String,
    /** A Lucide name, so the tabs match the glyphs used everywhere else. */
    val icon: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * The tabs, and the screen they hold.
 *
 * A bar underneath on a phone; a rail down the side once the window is
 * [RAIL_MIN_WIDTH] wide -- a tablet, or a phone on its side -- where a bar is
 * a strip of mostly nothing across the bottom and the height it takes is the
 * short dimension. [content] is handed the modifier that places it beside
 * whichever one is showing, and [showTabs] false gives it the whole window.
 */
@Composable
internal fun TabFrame(
    tabs: List<TabEntry>,
    showTabs: Boolean,
    snackbar: SnackbarHostState,
    content: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= RAIL_MIN_WIDTH
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showTabs && !rail) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = tab.selected,
                                onClick = tab.onClick,
                                icon = { TabIcon(tab) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            // A Row, not two siblings: the content slot is a box, and the rail
            // and the screen emitted into it directly would be drawn one on
            // top of the other.
            Row(Modifier.fillMaxSize()) {
                if (showTabs && rail) {
                    NavigationRail {
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = tab.selected,
                                onClick = tab.onClick,
                                icon = { TabIcon(tab) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
                content(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(if (showTabs && !rail) padding else PaddingValues()),
                )
            }
        }
    }
}

@Composable
private fun TabIcon(tab: TabEntry) {
    LucideGlyph(
        name = tab.icon,
        size = NAV_ICON,
        // Follows the bar's own selected/unselected colours instead of
        // picking its own.
        tint = LocalContentColor.current,
        contentDescription = tab.label,
    )
}

/** Material's own line between a compact window and a medium one. */
internal val RAIL_MIN_WIDTH = 600.dp

private val NAV_ICON = 24.dp
