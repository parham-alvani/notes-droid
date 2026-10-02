package me.parham1995.notes.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import me.parham1995.notes.markdown.HeadingFolds
import me.parham1995.notes.markdown.MdBlock
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.StylusSpotlight

/**
 * A whole note.
 *
 * The list is the renderer's central decision: one `LazyColumn` item per block,
 * each with its own `AnnotatedString`, so opening the largest note in a vault
 * composes only the handful of blocks on screen. Building one string for the
 * document would stall the frame that did it.
 */
@Composable
fun MarkdownDocument(
    blocks: List<MdBlock>,
    actions: RenderActions,
    modifier: Modifier = Modifier,
    brokenLinks: Set<String> = emptySet(),
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    /**
     * Pinching the page resizes the text. Null leaves the gesture alone, for
     * the places a document is shown but not read as one.
     */
    onPinch: ((Float) -> Unit)? = null,
    /** The block being read aloud, by its position, tinted so it can be followed. */
    spoken: Int? = null,
    /**
     * Headings whose sections are folded away, by position in [blocks]. The
     * page owns this, not the headings, so a fold survives scrolling off.
     */
    folded: Set<Int> = emptySet(),
    /** Folds or unfolds the heading at a position. Null, and headings do not fold. */
    onToggleFold: ((Int) -> Unit)? = null,
) {
    val reading = LocalReading.current
    // What is left once the folded sections are gone: positions in [blocks],
    // in order. The list is keyed by block id, so folding removes items rather
    // than re-composing everything under the fold.
    val shown = remember(blocks, folded) { HeadingFolds.visible(blocks, folded) }
    // Outside the SelectionContainer, so watching the pen cannot interfere
    // with the gesture that selects text.
    StylusSpotlight(
        enabled = reading.stylusSpotlight,
        modifier =
            modifier.pointerInput(onPinch) {
                if (onPinch == null) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var applied = false
                    do {
                        val event = awaitPointerEvent()
                        // Two fingers, and only once per gesture: the steps
                        // are coarse, so a continuous pinch would run through
                        // all of them in a moment.
                        if (!applied && event.changes.size >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom !in (1f - PINCH_SLOP)..(1f + PINCH_SLOP)) {
                                onPinch(zoom)
                                applied = true
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        tint = MaterialTheme.colorScheme.primary,
    ) {
        // Selection spans the document rather than each block, so copying a
        // paragraph and the heading above it works.
        SelectionContainer {
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = shown, key = { blocks[it].id }) { index ->
                    val block = blocks[index]
                    val tint =
                        if (index == spoken) {
                            Modifier.background(
                                MaterialTheme.colorScheme.primary.copy(alpha = SPOKEN_ALPHA),
                                MaterialTheme.shapes.small,
                            )
                        } else {
                            Modifier
                        }
                    // Only a heading with something under it gets a chevron:
                    // one with nothing to hide would fold to no visible effect.
                    val fold =
                        if (onToggleFold != null && HeadingFolds.hasSection(blocks, index)) {
                            Fold(folded = index in folded, toggle = { onToggleFold(index) })
                        } else {
                            null
                        }
                    Box(tint) { MdBlockView(block, actions, brokenLinks, fold = fold) }
                }
            }
        }
    }
}

/** Faint: it marks the paragraph, it does not repaint it. */
private const val SPOKEN_ALPHA = 0.12f

/** How far a pinch must go before it counts as one. */
private const val PINCH_SLOP = 0.15f
