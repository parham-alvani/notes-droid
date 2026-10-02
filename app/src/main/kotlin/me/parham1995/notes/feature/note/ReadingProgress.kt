package me.parham1995.notes.feature.note

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * How far down the note the reader is, as a hairline under the bar.
 *
 * The scrollbar a web page has and a `LazyColumn` does not. It is read from the
 * list's layout rather than kept as state, so it costs nothing when the list is
 * still, and it is a fixed two pixels tall whether or not it has anything to
 * say, so appearing does not nudge the page.
 */
@Composable
internal fun ReadingProgress(
    listState: LazyListState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress by
        remember(listState) {
            derivedStateOf {
                val info = listState.layoutInfo
                readingProgress(
                    total = info.totalItemsCount,
                    last = info.visibleItemsInfo.lastOrNull()?.let { Extent(it.index, it.offset, it.size) },
                    viewportEnd = info.viewportEndOffset - info.afterContentPadding,
                    canScrollForward = listState.canScrollForward,
                    canScrollBackward = listState.canScrollBackward,
                )
            }
        }
    Box(modifier.fillMaxWidth().height(PROGRESS_HEIGHT)) {
        val fraction = progress
        if (enabled && fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT),
                trackColor = Color.Transparent,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

private val PROGRESS_HEIGHT = 2.dp

/** Where the last item on screen is: its position, its top, and its height, in pixels. */
internal data class Extent(
    val index: Int,
    val offset: Int,
    val size: Int,
)

/**
 * The fraction of a list of [total] items that has scrolled past the bottom of
 * the viewport, given the [last] item showing and where the viewport ends.
 *
 * Measured from the bottom edge rather than the top so it reaches one exactly
 * when the last line does, and counted in items because that is what a lazy
 * list knows: it has not measured the blocks it has not drawn. Null when the
 * note fits on screen: a full bar over a page that does not scroll would only
 * say that it does not scroll.
 */
internal fun readingProgress(
    total: Int,
    last: Extent?,
    viewportEnd: Int,
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
): Float? {
    if (total == 0 || last == null) return null
    if (!canScrollForward && !canScrollBackward) return null
    if (!canScrollForward) return 1f
    val shownOfLast =
        if (last.size > 0) {
            ((viewportEnd - last.offset).toFloat() / last.size).coerceIn(0f, 1f)
        } else {
            1f
        }
    return ((last.index + shownOfLast) / total).coerceIn(0f, 1f)
}
