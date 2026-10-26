package me.parham1995.notes.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.parham1995.notes.R
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
 *
 * The rail can be folded away ([railHidden]), from a button at its top; a
 * small button in the bottom corner then brings it back. The bar on a phone
 * has no such button: it is already gone whenever a note is open.
 */
@Composable
internal fun TabFrame(
    tabs: List<TabEntry>,
    showTabs: Boolean,
    snackbar: SnackbarHostState,
    railHidden: Boolean = false,
    onRailHidden: (Boolean) -> Unit = {},
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
            // The glyphs point at the edge the rail sits on, which is the
            // right one in Persian.
            val side = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "right" else "left"
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = showTabs && rail && !railHidden,
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut(),
                    ) {
                        NavigationRail(
                            header = {
                                IconButton(onClick = { onRailHidden(true) }) {
                                    LucideGlyph(
                                        name = "panel-$side-close",
                                        size = NAV_ICON,
                                        tint = LocalContentColor.current,
                                        contentDescription = stringResource(R.string.nav_hide_rail),
                                    )
                                }
                            },
                        ) {
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
                    // Consumed as well as applied: plain padding leaves the insets
                    // in place for a bar inside the screen to add a second time.
                    // With the rail there is no Scaffold padding, and the screen's
                    // own bars take the insets they need.
                    val applied = if (showTabs && !rail) padding else PaddingValues()
                    content(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(applied)
                            .consumeWindowInsets(applied),
                    )
                }
                // In the bottom corner on the rail's side: the top bars hold back
                // and menu buttons in the top one, and a list's floating button
                // sits in the other bottom corner.
                AnimatedVisibility(
                    visible = showTabs && rail && railHidden,
                    modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(12.dp),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    FilledTonalIconButton(
                        onClick = { onRailHidden(false) },
                        modifier = Modifier.size(40.dp),
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                            ),
                    ) {
                        LucideGlyph(
                            name = "panel-$side-open",
                            size = 20.dp,
                            tint = LocalContentColor.current,
                            contentDescription = stringResource(R.string.nav_show_rail),
                        )
                    }
                }
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
